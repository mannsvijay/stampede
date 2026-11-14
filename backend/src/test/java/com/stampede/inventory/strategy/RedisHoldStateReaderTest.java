package com.stampede.inventory.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.stampede.control.invariants.Violation;
import com.stampede.inventory.api.RedisHoldState.CapMember;
import com.stampede.inventory.api.RedisHoldState.Hold;
import com.stampede.inventory.api.RedisHoldState.SeatValue;
import com.stampede.inventory.api.RedisHoldState.Snapshot;
import com.stampede.inventory.api.RedisHoldState.UnappliedEntry;
import com.stampede.testsupport.TestRedis;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisCallback;

/**
 * M2.2c-2: the real {@link RedisHoldStateReader} and {@code redis/state.lua} against Redis and Postgres.
 * What it reads is exactly what the other scripts wrote; it reads only its own key prefix; unapplied
 * stream entries are those the consumer group hasn't acknowledged; and invariant 5 (I10) fires through
 * the checker on real drift (every way it compares is made to fire in {@code RedisConsistencyCheckTest}).
 * Also measures how long the script blocks Redis on a loaded 2,000-seat event.
 */
class RedisHoldStateReaderTest extends RedisPersistenceTestSupport {

    static final Duration WINDOW = Duration.ofSeconds(2);

    @Autowired
    RedisHoldStateReader reader;

    @Test
    void anEventNotLoadedReadsAsEmpty() {
        assertThat(reader.read(EVENT, WINDOW)).isEmpty();
        assertThat(reader.reread(EVENT, WINDOW, List.of(SEATS.get(0)), List.of(), List.of())).isEmpty();
    }

    @Test
    void readsExactlyWhatTheScriptsWrote() {
        UUID orderId = sellDirectly(OTHER_USER, SEATS.get(0));
        UUID soldHold = jdbc.sql("SELECT hold_id FROM orders WHERE id = ?").param(orderId).query(UUID.class).single();
        load();
        UUID holdId = hold(USER, FIVE_MINUTES, SEATS.get(1), SEATS.get(2));
        RedisHoldStore.LiveHold live = store.liveHold(EVENT, holdId).orElseThrow();

        Snapshot snapshot = reader.read(EVENT, WINDOW).orElseThrow();

        assertThat(snapshot.now().toEpochMilli()).isCloseTo(store.nowMillis(), org.assertj.core.data.Offset.offset(1_000L));
        assertThat(snapshot.perUserLimit()).isEqualTo(LIMIT);
        assertThat(snapshot.seatIds()).isEqualTo(Set.copyOf(SEATS));
        assertThat(snapshot.seatValues()).isEqualTo(Map.of(
                SEATS.get(0), new SeatValue.Sold(orderId),
                SEATS.get(1), new SeatValue.Held(holdId),
                SEATS.get(2), new SeatValue.Held(holdId)));
        assertThat(snapshot.holds()).containsOnlyKeys(holdId);
        Hold hold = snapshot.holds().get(holdId);
        assertThat(hold.userId()).isEqualTo(USER);
        assertThat(hold.seatIds()).containsExactlyInAnyOrder(SEATS.get(1), SEATS.get(2));
        assertThat(hold.createdAt()).isEqualTo(live.createdAt());
        assertThat(hold.expiresAt()).isEqualTo(live.expiresAt());
        assertThat(hold.pinned()).isFalse();
        assertThat(snapshot.capMembers()).isEqualTo(Map.of(
                USER, List.of(new CapMember(holdId, 2, live.expiresAt())),
                OTHER_USER, List.of(new CapMember(soldHold, 1, null))));
        assertThat(snapshot.unappliedEntries()).singleElement().satisfies(entry -> {
            assertThat(entry.holdId()).isEqualTo(holdId);
            assertThat(entry.type()).isEqualTo("HELD");
        });

        drain();
        assertThat(reader.read(EVENT, WINDOW).orElseThrow().unappliedEntries()).as("acknowledged = applied").isEmpty();
        assertThat(violations()).isEmpty();
    }

    @Test
    void aDeliveredButUnacknowledgedEntryIsStillUnapplied() {
        load();
        UUID holdId = hold(USER, FIVE_MINUTES, SEATS.get(0));
        assertThat(readWithoutAck("crashed", 1)).hasSize(1);   // pending: delivered, never acknowledged

        assertThat(reader.read(EVENT, WINDOW).orElseThrow().unappliedEntries())
                .extracting(UnappliedEntry::holdId).containsExactly(holdId);

        drain();
        assertThat(reader.read(EVENT, WINDOW).orElseThrow().unappliedEntries()).isEmpty();
        assertThat(violations()).isEmpty();
    }

    @Test
    void entriesOlderThanTheWindowAreLeftOut() {
        load();
        hold(USER, FIVE_MINUTES, SEATS.get(0));
        long written = store.nowMillis();
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(2)).pollInterval(Duration.ofMillis(10))
                .until(() -> store.nowMillis() > written + 30);

        assertThat(reader.read(EVENT, Duration.ofMillis(20)).orElseThrow().unappliedEntries()).isEmpty();
        assertThat(reader.read(EVENT, WINDOW).orElseThrow().unappliedEntries()).hasSize(1);
        drain();
    }

    @Test
    void aHoldPinnedByASaleReadsAsPinnedAndCountedForever() {
        load();
        UUID holdId = hold(USER, FIVE_MINUTES, SEATS.get(0));
        drain();
        assertThat(store.pin(EVENT, holdId)).isInstanceOf(RedisHoldStore.Pin.Pinned.class);

        Snapshot snapshot = reader.read(EVENT, WINDOW).orElseThrow();
        assertThat(snapshot.holds().get(holdId).pinned()).isTrue();
        assertThat(snapshot.capMembers().get(USER)).containsExactly(new CapMember(holdId, 1, null));
        // Pinned in Redis, still ACTIVE and live in Postgres (a sale between steps 1 and 2): no disagreement.
        assertThat(violations()).isEmpty();
    }

    @Test
    void aReReadReadsOnlyTheListedKeysAndTheHoldsTheyName() {
        load();
        UUID first = hold(USER, FIVE_MINUTES, SEATS.get(0));
        UUID second = hold(OTHER_USER, FIVE_MINUTES, SEATS.get(1));
        drain();

        Snapshot snapshot = reader.reread(EVENT, WINDOW, List.of(SEATS.get(0)), List.of(), List.of()).orElseThrow();

        assertThat(snapshot.seatValues()).isEqualTo(Map.of(SEATS.get(0), new SeatValue.Held(first)));
        assertThat(snapshot.holds()).as("named by the seat").containsOnlyKeys(first);
        assertThat(snapshot.capMembers()).isEmpty();
        assertThat(snapshot.perUserLimit()).isEqualTo(LIMIT);
        assertThat(snapshot.seatIds()).isEqualTo(Set.copyOf(SEATS));

        Snapshot byUser = reader.reread(EVENT, WINDOW, List.of(), List.of(), List.of(OTHER_USER)).orElseThrow();
        assertThat(byUser.capMembers()).containsOnlyKeys(OTHER_USER);
        assertThat(byUser.holds()).as("named by the user's member").containsOnlyKeys(second);
        assertThat(byUser.seatValues()).isEmpty();
    }

    @Test
    void itReadsOnlyItsOwnKeyPrefix() {
        // Another application on the same Redis, with the same event and seat ids.
        RedisHoldStore other = new RedisHoldStore(redis, new RedisInventoryProperties("reader_isolation_other"));
        try {
            other.loadEvent(EVENT, SEATS, 9);
            other.hold(new HoldRequest(UUID.randomUUID(), EVENT, USER, List.of(SEATS.get(3)), FIVE_MINUTES));
            assertThat(reader.read(EVENT, WINDOW)).as("not loaded under OUR prefix").isEmpty();

            load();
            Snapshot snapshot = reader.read(EVENT, WINDOW).orElseThrow();
            assertThat(snapshot.perUserLimit()).isEqualTo(LIMIT);
            assertThat(snapshot.seatValues()).isEmpty();
            assertThat(snapshot.holds()).isEmpty();
            assertThat(snapshot.capMembers()).isEmpty();
            assertThat(snapshot.unappliedEntries()).isEmpty();
        } finally {
            TestRedis.deleteKeys(redis, "reader_isolation_other");
        }
    }

    // --- I10 through the checker, on real drift --------------------------------------------------

    @Test
    void i10FiresWhileRedisIsAheadAndClearsOnceTheStreamIsDrained() {
        load();
        UUID holdId = hold(USER, FIVE_MINUTES, SEATS.get(0));

        List<Violation> lagging = violations();
        assertThat(lagging).extracting(Violation::ruleId).containsOnly("I10");
        assertThat(lagging).hasSize(3).allMatch(v -> v.detail().contains("HELD entry for " + holdId + " unapplied"));
        // At runtime that is consumer lag under 2 s: excused.
        assertThat(invariants.check(EVENT, Duration.ofSeconds(30))).isEmpty();

        drain();
        assertThat(violations()).isEmpty();
    }

    @Test
    void i10FiresForAHoldOnlyPostgresHas() {
        load();
        UUID holdId = holdInPostgresOnly(USER, FIVE_MINUTES, SEATS.get(0));

        assertThat(violations()).extracting(Violation::ruleId).containsOnly("I10");
        assertThat(violations()).anyMatch(v -> v.detail().contains("hold " + holdId + " is ACTIVE in Postgres"));
        assertThat(invariants.check(EVENT, Duration.ofSeconds(30))).as("no stream entry explains it").isNotEmpty();

        // Leave both stores in agreement for the after-test check.
        jdbc.sql("UPDATE event_seats SET status = 'AVAILABLE', hold_id = NULL WHERE event_id = ?").param(EVENT).update();
        jdbc.sql("DELETE FROM holds WHERE id = ?").param(holdId).update();
        jdbc.sql("DELETE FROM user_event_allowance WHERE event_id = ?").param(EVENT).update();
        assertThat(violations()).isEmpty();
    }

    // --- cost ------------------------------------------------------------------------------------

    /**
     * How long {@code state.lua} blocks Redis on the demo event's size: 2,000 seats, all held by 500
     * buyers with one 4-seat hold each (every seat key, hold hash and user set exists). Redis' own
     * SLOWLOG gives the time spent executing the script (server side, no network). Printed; the bound
     * is loose so CI noise can't fail it.
     */
    @Test
    @SuppressWarnings("unchecked")
    void stateScriptTimeOnALoaded2000SeatEvent() {
        UUID event = id("timing-event");
        jdbc.sql("""
                INSERT INTO users (id, email, password_hash)
                SELECT md5('timing-user' || k)::uuid, 'timing' || k || '@example.test', 'x' FROM generate_series(1, 500) k
                """).update();
        jdbc.sql("""
                INSERT INTO seats (id, section_id, row_label, number, x, y)
                SELECT md5('timing-seat' || g)::uuid, :section, 'T', g, 0, 0 FROM generate_series(1, 2000) g
                """).param("section", SECTION).update();
        jdbc.sql("""
                INSERT INTO events (id, venue_id, title, starts_at, drop_at, status, per_user_limit, strategy)
                VALUES (?, ?, 'Timing', now() + interval '30 days', now() - interval '1 hour', 'LIVE', 4, 'REDIS_FIRST')
                """).params(event, VENUE).update();
        UUID tier = id("timing-tier");
        jdbc.sql("INSERT INTO price_tiers (id, event_id, name, price_paise) VALUES (?, ?, 'GOLD', 150000)")
                .params(tier, event).update();
        // Times in whole milliseconds, as Postgres gets them from Redis.
        jdbc.sql("""
                INSERT INTO holds (id, event_id, user_id, seat_ids, created_at, expires_at, status)
                SELECT md5('timing-hold' || k)::uuid, :event, md5('timing-user' || k)::uuid,
                       ARRAY(SELECT md5('timing-seat' || g)::uuid FROM generate_series(4 * k - 3, 4 * k) g),
                       date_trunc('milliseconds', now()), date_trunc('milliseconds', now()) + interval '5 minutes',
                       'ACTIVE'
                FROM generate_series(1, 500) k
                """).param("event", event).update();
        jdbc.sql("""
                INSERT INTO event_seats (event_id, seat_id, tier_id, status, hold_id, version)
                SELECT :event, md5('timing-seat' || g)::uuid, :tier, 'HELD', md5('timing-hold' || ((g - 1) / 4 + 1))::uuid, 1
                FROM generate_series(1, 2000) g
                """).param("event", event).param("tier", tier).update();
        jdbc.sql("""
                INSERT INTO user_event_allowance (event_id, user_id, used)
                SELECT :event, md5('timing-user' || k)::uuid, 4 FROM generate_series(1, 500) k
                """).param("event", event).update();
        assertThat(loader.ensureLoaded(event)).isTrue();
        long keysInRedis = redis.execute((RedisCallback<Long>) connection -> connection.serverCommands().dbSize());

        java.util.Properties config = redis.execute((RedisCallback<java.util.Properties>) c -> c.serverCommands()
                .getConfig("slowlog-log-slower-than"));
        String previous = config == null ? "10000" : config.getProperty("slowlog-log-slower-than", "10000");
        List<Long> serverMicros = new ArrayList<>();
        List<Long> roundTripMicros = new ArrayList<>();
        try {
            // 500 us, not 0: Redis 7 also logs every command run INSIDE a script, which would fill the
            // 128-entry log and add its own cost. Only the script itself takes that long.
            setSlowlogThreshold("500");
            for (int run = 0; run < 11; run++) {
                slowlog(server -> server.slowlogReset());
                long start = System.nanoTime();
                Snapshot snapshot = reader.read(event, WINDOW).orElseThrow();
                roundTripMicros.add((System.nanoTime() - start) / 1_000);
                assertThat(snapshot.seatValues()).hasSize(2000);
                assertThat(snapshot.holds()).hasSize(500);
                assertThat(snapshot.capMembers()).hasSize(500);
                // Each entry: id, unix time, execution time in microseconds, the command's arguments, ...
                for (Object entry : (List<Object>) slowlog(server -> server.slowlogGet(128))) {
                    List<Object> fields = (List<Object>) entry;
                    List<Object> args = (List<Object>) fields.get(3);
                    if (run > 0 && text(args.get(0)).toUpperCase().startsWith("EVAL")) {  // run 0 loads the script
                        serverMicros.add((Long) fields.get(2));
                    }
                }
            }
        } finally {
            setSlowlogThreshold(previous);
        }
        List<Long> server = serverMicros.stream().sorted().toList();
        List<Long> roundTrip = roundTripMicros.stream().skip(1).sorted().toList();  // run 0 loads the script
        long medianServer = server.isEmpty() ? -1 : server.get(server.size() / 2);
        System.out.println("[state.lua timing] 2000 seats, 500 holds, 500 users, " + keysInRedis
                + " keys in the Redis db: server-side median " + medianServer / 1000.0 + " ms (10 runs, us: " + server
                + "); round trip incl. parsing median " + roundTrip.get(roundTrip.size() / 2) / 1000.0 + " ms");

        assertThat(server).as("SLOWLOG captured every script run (each took over 500 us)").hasSize(10);
        assertThat(medianServer).as("median server-side microseconds").isLessThan(500_000);
        assertThat(invariants.check(event, Duration.ZERO)).as("loaded from Postgres: both stores agree").isEmpty();
    }

    private void setSlowlogThreshold(String micros) {
        redis.execute((RedisCallback<Object>) c -> {
            c.serverCommands().setConfig("slowlog-log-slower-than", micros);
            return null;
        });
    }

    /** SLOWLOG through Lettuce itself: Spring Data Redis has no API for it. */
    private Object slowlog(java.util.function.Function<io.lettuce.core.api.async.RedisServerAsyncCommands<byte[], byte[]>,
            io.lettuce.core.RedisFuture<?>> command) {
        return redis.execute((RedisCallback<Object>) c -> {
            @SuppressWarnings("unchecked")
            var server = (io.lettuce.core.api.async.RedisServerAsyncCommands<byte[], byte[]>) c.getNativeConnection();
            try {
                return command.apply(server).get(5, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
    }

    private static String text(Object value) {
        return value instanceof byte[] bytes ? new String(bytes, java.nio.charset.StandardCharsets.UTF_8)
                : String.valueOf(value);
    }
}
