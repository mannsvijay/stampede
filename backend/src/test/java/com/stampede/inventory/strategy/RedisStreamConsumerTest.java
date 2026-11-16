package com.stampede.inventory.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.stampede.inventory.seat.SeatStatus;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.dao.QueryTimeoutException;

/**
 * M2.2b: the stream consumer. Lag and catch-up, retrying a failed write, idempotent replay, a crash
 * between applying and acknowledging, poison entries, trimming, and a lost stream.
 */
class RedisStreamConsumerTest extends RedisPersistenceTestSupport {

    @Test
    void lagBuildsUpWhileNothingConsumesAndCatchUpPersistsEveryHold() {
        load();
        List<UUID> holds = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            holds.add(hold(USERS.get(i % USERS.size()), FIVE_MINUTES, SEATS.get(i)));
        }

        RedisStreamConsumer.Lag lag = consumer.refreshLag();
        assertThat(lag.entries()).isEqualTo(20);
        assertThat(meters.get(RedisStreamMetrics.LAG).gauge().value()).isEqualTo(20);
        assertThat(jdbc.sql("SELECT count(*) FROM holds").query(Integer.class).single()).isZero();

        drain();

        lag = consumer.refreshLag();
        assertThat(lag.entries()).isZero();
        assertThat(lag.oldestAgeMillis()).isZero();
        assertThat(meters.get(RedisStreamMetrics.LAG).gauge().value()).isZero();
        for (int i = 0; i < holds.size(); i++) {
            assertThat(holdStatus(holds.get(i))).isEqualTo("ACTIVE");
            assertThat(seat(SEATS.get(i)).holdId()).isEqualTo(holds.get(i));
        }
        assertThat(USERS.stream().mapToInt(this::allowance).sum()).isEqualTo(20);
        assertThat(counter(RedisStreamMetrics.APPLIED, "type", "held", "result", "applied")).isEqualTo(20);
        assertThat(violations()).isEmpty();
    }

    @Test
    void theOldestUnacknowledgedEntryAgesWhileItWaits() {
        load();
        hold(USER, FIVE_MINUTES, SEATS.get(0));

        await().atMost(Duration.ofSeconds(5)).until(() -> consumer.refreshLag().oldestAgeMillis() >= 200);
        assertThat(meters.get(RedisStreamMetrics.OLDEST_AGE).gauge().value()).isGreaterThanOrEqualTo(200);

        drain();
        assertThat(consumer.refreshLag().oldestAgeMillis()).isZero();
    }

    @Test
    void aFailedWriteStaysPendingAndIsAppliedOnTheRetry() {
        load();
        UUID holdId = hold(USER, FIVE_MINUTES, SEATS.get(0), SEATS.get(1));
        failHoldInserts();

        consumer.pollOnce("a");

        assertThat(holdRow(holdId)).isEmpty();
        assertThat(pendingCount()).as("not acknowledged").isEqualTo(1);
        assertThat(deadLetters()).isEmpty();

        stopFailingHoldInserts();
        assertThat(consumer.reclaimOnce("a")).isEqualTo(1);

        assertThat(holdStatus(holdId)).isEqualTo("ACTIVE");
        assertThat(seat(SEATS.get(0)).holdId()).isEqualTo(holdId);
        assertThat(allowance(USER)).isEqualTo(2);
        assertThat(pendingCount()).isZero();
    }

    @Test
    void replayingTheWholeStreamChangesNothing() {
        load();
        UUID kept = hold(USER, FIVE_MINUTES, SEATS.get(0), SEATS.get(1));
        UUID released = hold(OTHER_USER, FIVE_MINUTES, SEATS.get(2));
        releaseInRedis(released);
        UUID failed = hold(OTHER_USER, FIVE_MINUTES, SEATS.get(2), SEATS.get(3));
        assertThat(store.release(EVENT, failed, ReleaseReason.SAGA_FAILED))
                .isInstanceOf(RedisHoldStore.Release.Released.class);
        drain();
        List<String> before = postgresState();

        // Every entry delivered again from the start, as after a group reset.
        redisCommand("XGROUP", "SETID", store.keys().stream(), RedisKeys.GROUP, "0");
        drain();

        assertThat(postgresState()).isEqualTo(before);
        assertThat(holdStatus(kept)).isEqualTo("ACTIVE");
        assertThat(holdStatus(released)).isEqualTo("RELEASED");
        assertThat(holdStatus(failed)).isEqualTo("RELEASED");
        // Replay: all three HELD entries find their row. Both RELEASED entries were duplicates already
        // in the first drain (each hold was over in Redis before its HELD was applied, so the HELD
        // wrote the tombstone) and are again now: 2 + 2.
        assertThat(counter(RedisStreamMetrics.APPLIED, "type", "held", "result", "duplicate")).isEqualTo(3);
        assertThat(counter(RedisStreamMetrics.APPLIED, "type", "held", "result", "stale")).isEqualTo(2);
        assertThat(counter(RedisStreamMetrics.APPLIED, "type", "released", "result", "duplicate")).isEqualTo(4);
        assertThat(violations()).isEmpty();
    }

    @Test
    void aCrashBetweenApplyingAndAcknowledgingIsHarmless() {
        load();
        UUID holdId = hold(USER, FIVE_MINUTES, SEATS.get(0));

        // Consumer "a" reads the entry, commits it to Postgres, and dies before XACK.
        List<MapRecord<String, Object, Object>> read = readWithoutAck("a", 10);
        assertThat(read).hasSize(1);
        writer.apply(entry(read.get(0)));
        List<String> applied = postgresState();
        assertThat(pendingCount()).isEqualTo(1);

        // Consumer "b" takes the entry over and applies it again.
        assertThat(consumer.reclaimOnce("b")).isEqualTo(1);

        assertThat(postgresState()).isEqualTo(applied);
        assertThat(holdStatus(holdId)).isEqualTo("ACTIVE");
        assertThat(allowance(USER)).isEqualTo(1);
        assertThat(pendingCount()).isZero();
        assertThat(counter(RedisStreamMetrics.APPLIED, "type", "held", "result", "duplicate")).isEqualTo(1);
    }

    @Test
    void aRestartedConsumerReappliesItsOwnPendingEntriesFirst() {
        load();
        UUID holdId = hold(USER, FIVE_MINUTES, SEATS.get(0));
        assertThat(readWithoutAck("a", 10)).hasSize(1);   // "a" crashed before applying anything

        assertThat(consumer.readOwnPending("a")).isEqualTo(1);

        assertThat(holdStatus(holdId)).isEqualTo("ACTIVE");
        assertThat(pendingCount()).isZero();
    }

    @Test
    void aMalformedEntryGoesToTheDeadLetterStreamAfterFiveDeliveries() {
        load();
        redis.opsForStream().add(StreamRecords.newRecord().in(store.keys().stream())
                .ofMap(Map.of("type", "HELD", "event", EVENT.toString(), "hold", "not-a-uuid")));
        UUID good = hold(USER, FIVE_MINUTES, SEATS.get(0));

        consumer.pollOnce("a");   // delivery 1: fails, stays pending; the good entry after it is applied
        assertThat(holdStatus(good)).isEqualTo("ACTIVE");
        assertThat(pendingCount()).isEqualTo(1);
        for (int delivery = 2; delivery <= 4; delivery++) {
            consumer.reclaimOnce("a");
            assertThat(pendingCount()).as("after delivery %d", delivery).isEqualTo(1);
            assertThat(deadLetters()).isEmpty();
        }

        consumer.reclaimOnce("a");   // delivery 5

        assertThat(pendingCount()).isZero();
        assertThat(deadLetters()).hasSize(1);
        Map<Object, Object> dead = deadLetters().get(0).getValue();
        assertThat(dead).containsEntry("hold", "not-a-uuid").containsEntry("deliveries", "5")
                .containsEntry("consumer", "a").containsKey("sourceId");
        assertThat((String) dead.get("error")).contains("Malformed stream entry");
        assertThat(counter(RedisStreamMetrics.DEAD_LETTERS)).isEqualTo(1);
    }

    @Test
    void aDeadLetteredHoldIsRolledForwardByTheReconcilerOnceTheCauseIsFixed() {
        load();
        UUID holdId = hold(USER, FIVE_MINUTES, SEATS.get(0));
        failHoldInserts();
        consumer.pollOnce("a");
        for (int delivery = 2; delivery <= 5; delivery++) {
            consumer.reclaimOnce("a");
        }
        assertThat(deadLetters()).hasSize(1);
        assertThat(pendingCount()).isZero();
        assertThat(holdRow(holdId)).isEmpty();

        stopFailingHoldInserts();
        await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(100))
                .until(() -> reconciler.reconcileOnce().rolledForward() == 1);

        assertThat(holdStatus(holdId)).isEqualTo("ACTIVE");
        assertThat(seat(SEATS.get(0)).status()).isEqualTo(SeatStatus.HELD);
        assertThat(allowance(USER)).isEqualTo(1);
        assertThat(violations()).isEmpty();
    }

    @Test
    void transientFailuresAreRecognisedAndNeverCountTowardsGivingUp() {
        assertThat(RedisStreamConsumer.isTransient(new CannotGetJdbcConnectionException("down"))).isTrue();
        assertThat(RedisStreamConsumer.isTransient(new RedisConnectionFailureException("down"))).isTrue();
        assertThat(RedisStreamConsumer.isTransient(new QueryTimeoutException("slow"))).isTrue();
        assertThat(RedisStreamConsumer.isTransient(new IllegalStateException("wrapped",
                new CannotGetJdbcConnectionException("down")))).isTrue();
        assertThat(RedisStreamConsumer.isTransient(new DataIntegrityViolationException("bad row"))).isFalse();
        assertThat(RedisStreamConsumer.isTransient(new IllegalArgumentException("malformed"))).isFalse();
    }

    @Test
    void trimmingDeletesOnlyEntriesEveryConsumerIsDoneWith() {
        load();
        for (int i = 0; i < 3; i++) {
            hold(USER, FIVE_MINUTES, SEATS.get(i));
        }
        drain();
        hold(OTHER_USER, FIVE_MINUTES, SEATS.get(3));
        hold(OTHER_USER, FIVE_MINUTES, SEATS.get(4));
        assertThat(readWithoutAck("a", 1)).hasSize(1);   // one delivered, not acknowledged; one not delivered
        assertThat(streamLength()).isEqualTo(5);

        assertThat(consumer.trimOnce()).isEqualTo(3);

        assertThat(streamLength()).as("the pending entry and the undelivered one stay").isEqualTo(2);
        drain();
        assertThat(holdRowCount("ACTIVE")).isEqualTo(5);

        consumer.trimOnce();
        assertThat(streamLength()).as("only the last delivered entry is left").isEqualTo(1);
    }

    @Test
    void aLostStreamAndGroupAreCreatedAgain() {
        load();
        redis.delete(store.keys().stream());
        assertThat(consumer.pollOnce("a")).isZero();   // NOGROUP on an empty stream: no exception

        // A hold recreates the stream without the group: the next read recreates the group and reads.
        redis.delete(store.keys().stream());
        UUID holdId = hold(USER, FIVE_MINUTES, SEATS.get(0));
        assertThat(consumer.pollOnce("a")).isEqualTo(1);
        assertThat(holdStatus(holdId)).isEqualTo("ACTIVE");
    }

    // ---- connections (review follow-up, 2026-11-16) ----

    /**
     * A blocking XREADGROUP runs on a dedicated Lettuce connection, not the shared one. Without reuse,
     * every read opened and closed its own TCP connection (about 50 a second in the concurrency tests,
     * each left in TIME_WAIT on the client). Each consumer name now keeps one connection for its reads.
     */
    @Test
    void blockingReadsReuseOneConnectionPerConsumer() {
        load();
        consumer.pollOnce("a");   // the first read of each consumer may open its connection
        consumer.pollOnce("b");
        long before = connectionsReceived();

        int applied = 0;
        for (int i = 0; i < 30; i++) {
            UUID holdId = hold(USERS.get(i % 2), FIVE_MINUTES, SEATS.get(i % SEATS.size()));
            applied += consumer.pollOnce(i % 2 == 0 ? "a" : "b");
            applied += consumer.pollOnce(i % 2 == 0 ? "b" : "a");   // nothing new: a blocking read that times out
            releaseInRedis(holdId);
            drain();
        }

        assertThat(applied).isEqualTo(30);
        assertThat(connectionsReceived() - before).as("new Redis connections during 60 blocking reads").isZero();
    }

    /** A consumer whose connection breaks gets a new one and goes on applying. */
    @Test
    void aConsumerWhoseConnectionWasKilledReconnectsAndKeepsApplying() {
        load();
        consumer.pollOnce("a");
        String readers = redis.execute((org.springframework.data.redis.core.RedisCallback<String>) connection -> {
            Object list = connection.execute("CLIENT", "LIST".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return list instanceof byte[] raw ? new String(raw, java.nio.charset.StandardCharsets.UTF_8) : String.valueOf(list);
        });
        List<String> blockingReaders = readers.lines().filter(line -> line.contains("cmd=xreadgroup")).toList();
        assertThat(blockingReaders).as("the consumer's own connection").isNotEmpty();
        for (String line : blockingReaders) {
            int start = line.indexOf(" addr=") + " addr=".length();
            String addr = line.substring(start, line.indexOf(' ', start));   // host:port of the client
            int colon = addr.lastIndexOf(':');
            redis.execute((org.springframework.data.redis.core.RedisCallback<Object>) connection -> {
                connection.serverCommands().killClient(addr.substring(0, colon), Integer.parseInt(addr.substring(colon + 1)));
                return null;
            });
        }

        UUID holdId = hold(USER, FIVE_MINUTES, SEATS.get(0));
        int failures = 0;
        for (int attempt = 0; attempt < 3 && !"ACTIVE".equals(holdStatus(holdId)); attempt++) {
            try {
                consumer.pollOnce("a");
            } catch (RuntimeException e) {
                failures++;   // at most the read that found the connection dead
            }
        }

        assertThat(holdStatus(holdId)).isEqualTo("ACTIVE");
        assertThat(failures).isLessThanOrEqualTo(1);
    }

    private long connectionsReceived() {
        java.util.Properties stats = redis.execute((org.springframework.data.redis.core.RedisCallback<java.util.Properties>)
                connection -> connection.serverCommands().info("stats"));
        return Long.parseLong(stats.getProperty("total_connections_received"));
    }

    private int holdRowCount(String status) {
        return jdbc.sql("SELECT count(*) FROM holds WHERE status = ?").param(status).query(Integer.class).single();
    }
}
