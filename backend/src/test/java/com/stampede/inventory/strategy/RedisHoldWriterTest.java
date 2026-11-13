package com.stampede.inventory.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.stampede.inventory.seat.SeatStatus;
import com.stampede.inventory.strategy.RedisHoldWriter.Applied;
import com.stampede.testsupport.TestRedis;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.stream.MapRecord;

/**
 * M2.2b: applying stream entries to Postgres. Out-of-order redelivery, holds that are already over in
 * Redis, closing an older hold first, Postgres winning conflicts, and expiry for the sweeper.
 */
class RedisHoldWriterTest extends RedisPersistenceTestSupport {

    // ---- out of order: a released hold never comes back ----

    @Test
    void aHeldRedeliveredAfterItsReleasedWasAppliedLeavesTheHoldReleased() {
        load();
        UUID holdId = hold(USER, FIVE_MINUTES, SEATS.get(0), SEATS.get(1));
        releaseInRedis(holdId);
        List<MapRecord<String, Object, Object>> entries = readWithoutAck("a", 10);
        assertThat(entries).hasSize(2);
        StreamEntry held = entry(entries.get(0));
        StreamEntry released = entry(entries.get(1));
        assertThat(held.type()).isEqualTo(StreamEntry.Type.HELD);

        // The RELEASED is applied first (consumer "a" crashed before the HELD): Postgres has no row yet,
        // so the release writes the hold as already over.
        assertThat(writer.apply(released)).isEqualTo(Applied.STALE);
        assertThat(holdStatus(holdId)).isEqualTo("RELEASED");

        // The HELD arrives later (reclaimed): the row exists, so nothing changes.
        assertThat(writer.apply(held)).isEqualTo(Applied.DUPLICATE);
        assertThat(consumer.reclaimOnce("b")).isEqualTo(2);   // both again, through the consumer

        assertThat(holdStatus(holdId)).isEqualTo("RELEASED");
        assertThat(seat(SEATS.get(0))).isEqualTo(new SeatRow(SeatStatus.AVAILABLE, null, null, 0));
        assertThat(seat(SEATS.get(1))).isEqualTo(new SeatRow(SeatStatus.AVAILABLE, null, null, 0));
        assertThat(allowance(USER)).isZero();
        assertThat(pendingCount()).isZero();
        assertThat(violations()).isEmpty();
    }

    @Test
    void aHeldEvenWithoutItsReleasedIsNotPersistedAsActiveOnceRedisReleasedTheHold() {
        load();
        UUID holdId = hold(USER, FIVE_MINUTES, SEATS.get(0));
        StreamEntry held = entry(readWithoutAck("a", 1).get(0));
        releaseInRedis(holdId);   // its RELEASED entry is still unread

        assertThat(writer.apply(held)).isEqualTo(Applied.STALE);

        assertThat(holdStatus(holdId)).as("released before its time ran out").isEqualTo("RELEASED");
        assertThat(seat(SEATS.get(0)).status()).isEqualTo(SeatStatus.AVAILABLE);
        drain("b");
        assertThat(holdStatus(holdId)).isEqualTo("RELEASED");
        assertThat(violations()).isEmpty();
    }

    @Test
    void anOlderHeldReplayedAfterANewerHoldOnTheSameSeatIsRecordedAsExpired() {
        load();
        UUID older = hold(USER, ONE_SECOND, SEATS.get(0));
        StreamEntry olderHeld = entry(readWithoutAck("a", 1).get(0));   // "a" dies holding it
        waitForRedisExpiry(older);
        UUID newer = hold(OTHER_USER, FIVE_MINUTES, SEATS.get(0));
        consumer.pollOnce("b");
        assertThat(holdStatus(newer)).isEqualTo("ACTIVE");

        assertThat(writer.apply(olderHeld)).isEqualTo(Applied.STALE);

        assertThat(holdStatus(older)).isEqualTo("EXPIRED");
        assertThat(seat(SEATS.get(0)).holdId()).isEqualTo(newer);
        assertThat(allowance(USER)).isZero();
        assertThat(allowance(OTHER_USER)).isEqualTo(1);
        assertThat(violations()).isEmpty();
    }

    // ---- an older hold that Redis expired is closed first ----

    @Test
    void aNewHoldOnASeatPostgresStillShowsHeldByAnExpiredHoldClosesThatHoldFirst() {
        load();
        UUID older = hold(USER, ONE_SECOND, SEATS.get(0), SEATS.get(1));
        drain();
        waitForRedisExpiry(older);   // gone in Redis; the sweeper (off in tests) never closed it
        assertThat(holdStatus(older)).isEqualTo("ACTIVE");

        UUID newer = hold(OTHER_USER, FIVE_MINUTES, SEATS.get(1));
        drain();

        assertThat(holdStatus(older)).isEqualTo("EXPIRED");
        assertThat(holdStatus(newer)).isEqualTo("ACTIVE");
        assertThat(seat(SEATS.get(0)).status()).isEqualTo(SeatStatus.AVAILABLE);
        assertThat(seat(SEATS.get(1)).holdId()).isEqualTo(newer);
        assertThat(allowance(USER)).isZero();
        assertThat(allowance(OTHER_USER)).isEqualTo(1);
        assertThat(violations()).isEmpty();
    }

    @Test
    void theCapInPostgresStopsCountingAHoldThatExpiredInRedis() {
        load();
        UUID older = hold(USER, ONE_SECOND, SEATS.get(0), SEATS.get(1), SEATS.get(2), SEATS.get(3));
        drain();
        waitForRedisExpiry(older);
        // Redis recounts the cap from live holds, so it allows four more; Postgres still counts four.
        UUID newer = hold(USER, FIVE_MINUTES, SEATS.get(4), SEATS.get(5), SEATS.get(6), SEATS.get(7));

        drain();

        assertThat(holdStatus(older)).isEqualTo("EXPIRED");
        assertThat(holdStatus(newer)).isEqualTo("ACTIVE");
        assertThat(allowance(USER)).isEqualTo(4);
        assertThat(violations()).isEmpty();
    }

    // ---- Postgres wins ----

    @Test
    void aRedisHoldOnASeatPostgresHasSoldIsReleasedInRedisAndRecordedAsReleased() {
        // Redis doesn't know the seat is sold (loaded before the sale, or lost its marker).
        load();
        UUID orderId = sellDirectly(OTHER_USER, SEATS.get(0));
        UUID holdId = hold(USER, FIVE_MINUTES, SEATS.get(0), SEATS.get(1));

        drain();

        assertThat(holdStatus(holdId)).isEqualTo("RELEASED");
        assertThat(seat(SEATS.get(0)).status()).isEqualTo(SeatStatus.SOLD);
        assertThat(seat(SEATS.get(1))).isEqualTo(new SeatRow(SeatStatus.AVAILABLE, null, null, 0));
        assertThat(store.liveHold(EVENT, holdId)).isEmpty();
        assertThat(store.seatValue(EVENT, SEATS.get(0))).startsWith("S:" + orderId + ":");
        assertThat(store.seatValue(EVENT, SEATS.get(1))).isNull();
        assertThat(store.used(EVENT, USER)).isZero();
        assertThat(allowance(USER)).isZero();
        assertThat(counter(RedisStreamMetrics.APPLIED, "type", "held", "result", "conflict")).isEqualTo(1);
        assertThat(violations()).isEmpty();
    }

    @Test
    void whenRedisUndercountsTheCapPostgresWins() {
        load();
        UUID first = hold(USER, FIVE_MINUTES, SEATS.get(0), SEATS.get(1), SEATS.get(2), SEATS.get(3));
        drain();
        // Redis loses the user's cap member (partial data loss) but still has the hold itself.
        redis.opsForZSet().remove(store.keys().user(EVENT, USER), first + ":4");
        UUID second = hold(USER, FIVE_MINUTES, SEATS.get(4));

        drain();

        assertThat(holdStatus(first)).as("live in Redis, so not closed").isEqualTo("ACTIVE");
        assertThat(holdStatus(second)).isEqualTo("RELEASED");
        assertThat(store.liveHold(EVENT, second)).isEmpty();
        assertThat(seat(SEATS.get(4)).status()).isEqualTo(SeatStatus.AVAILABLE);
        assertThat(allowance(USER)).isEqualTo(4);
        assertThat(violations()).isEmpty();
    }

    @Test
    void aSeatRedisHasButPostgresDoesNotIsAConflict() {
        UUID extra = id("not-in-postgres");
        store.loadEvent(EVENT, List.of(SEATS.get(0), extra), LIMIT);
        UUID holdId = hold(USER, FIVE_MINUTES, SEATS.get(0), extra);

        drain();

        assertThat(holdStatus(holdId)).isEqualTo("RELEASED");
        assertThat(seat(SEATS.get(0)).status()).isEqualTo(SeatStatus.AVAILABLE);
        assertThat(store.liveHold(EVENT, holdId)).isEmpty();
    }

    // ---- expiry, as the sweeper will call it (M2.2c) ----

    @Test
    void expiryAsksRedisFirstAndClosesOnlyWhatRedisNoLongerHas() {
        load();
        UUID holdId = hold(USER, ONE_SECOND, SEATS.get(0));
        drain();

        assertThat(writer.expire(EVENT, holdId)).isInstanceOf(ReleaseOutcome.NotExpired.class);
        assertThat(holdStatus(holdId)).isEqualTo("ACTIVE");

        waitForRedisExpiry(holdId);
        assertThat(writer.expire(EVENT, holdId)).isEqualTo(new ReleaseOutcome.Released(List.of(SEATS.get(0))));
        assertThat(holdStatus(holdId)).isEqualTo("EXPIRED");
        assertThat(seat(SEATS.get(0)).status()).isEqualTo(SeatStatus.AVAILABLE);
        assertThat(allowance(USER)).isZero();

        assertThat(writer.expire(EVENT, holdId)).isEqualTo(new ReleaseOutcome.NotActive("EXPIRED"));
        assertThat(writer.expire(EVENT, UUID.randomUUID())).isEqualTo(new ReleaseOutcome.NotFound());
        assertThat(violations()).isEmpty();
    }

    @Test
    void expiryLeavesHoldsAloneWhileRedisDoesNotHaveTheEventLoaded() {
        load();
        UUID holdId = hold(USER, FIVE_MINUTES, SEATS.get(0));
        drain();
        TestRedis.deleteKeys(redis, PREFIX);   // Redis restarted without its data

        assertThat(writer.expire(EVENT, holdId)).isInstanceOf(ReleaseOutcome.NotExpired.class);
        assertThat(holdStatus(holdId)).isEqualTo("ACTIVE");
    }

    // ---- the load-epoch guard on "close older holds Redis no longer has" (M2.2c) ----

    @Test
    void aReloadWhileClosingAnOlderHoldStopsTheCloseAndTheReloadedHoldSurvives() {
        load();
        UUID older = hold(USER, FIVE_MINUTES, SEATS.get(0));
        drain();
        // Redis loses the older hold without an entry (partial loss), so a newer hold takes its seat.
        redis.delete(List.of(store.keys().hold(EVENT, older), store.keys().seat(EVENT, SEATS.get(0))));
        UUID newer = hold(OTHER_USER, FIVE_MINUTES, SEATS.get(0));
        StreamEntry.HoldFacts newerFacts = StreamEntry.HoldFacts.of(EVENT, store.liveHold(EVENT, newer).orElseThrow());
        // At the one moment the guard protects (after "Redis doesn't have the older hold", before the
        // close), Redis is wiped and reloaded from Postgres, which still has the older hold ACTIVE.
        writer.beforeEpochCheck(() -> {
            TestRedis.deleteKeys(redis, PREFIX);
            loader.ensureLoaded(EVENT);
        });
        Applied applied;
        try {
            applied = writer.applyHeld(newerFacts);
        } finally {
            writer.beforeEpochCheck(() -> { });
        }

        // Without the guard the older hold would be closed although the reload just restored it.
        assertThat(holdStatus(older)).isEqualTo("ACTIVE");
        assertThat(store.liveHold(EVENT, older)).isPresent();
        assertThat(seat(SEATS.get(0)).holdId()).isEqualTo(older);
        // The newer hold is still blocked after the reload: Postgres wins (D-022).
        assertThat(applied).isEqualTo(Applied.CONFLICT);
        assertThat(holdStatus(newer)).isEqualTo("RELEASED");
        assertThat(violations()).isEmpty();
    }

    // ---- lock-first expiry of a pinned hold (M2.2c) ----

    @Test
    void expiryFreesAPinnedHoldWhoseSaleNeverFinishedOnceItsTimeRanOut() {
        load();
        UUID holdId = hold(USER, ONE_SECOND, SEATS.get(0));
        drain();
        store.pin(EVENT, holdId);   // and then the process crashed before the Postgres sale

        assertThat(writer.expire(EVENT, holdId)).isInstanceOf(ReleaseOutcome.NotExpired.class);
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5))
                .pollInterval(java.time.Duration.ofMillis(50))
                .until(() -> store.nowMillis() > holdRow(holdId).orElseThrow().expiresAt().toEpochMilli() + 50);

        assertThat(writer.expire(EVENT, holdId)).isEqualTo(new ReleaseOutcome.Released(List.of(SEATS.get(0))));
        assertThat(holdStatus(holdId)).isEqualTo("EXPIRED");
        assertThat(store.liveHold(EVENT, holdId)).isEmpty();
        assertThat(store.seatValue(EVENT, SEATS.get(0))).isNull();
        assertThat(store.used(EVENT, USER)).isZero();
        assertThat(allowance(USER)).isZero();
        assertThat(violations()).isEmpty();
    }
}
