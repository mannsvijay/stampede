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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisCallback;

/**
 * M2.2c-2: the real {@link RedisHoldStateReader} and {@code redis/state.lua} against Redis and Postgres.
 * What it reads is exactly what the other scripts wrote; it reads only its own key prefix; unapplied
 * stream entries are those the consumer group hasn't acknowledged; and invariant 5 (I10) fires through
 * the checker on real drift (every way it compares is made to fire in {@code RedisConsistencyCheckTest}).
 * <p>
 * M2.2d: the first read is taken in pages. It reads the same as one atomic read when nothing changes;
 * a change between any two of its steps is cleared by I10's re-read; real drift is still reported while
 * other keys change; a reload during the read is noticed. Also measures how much a read blocks Redis on a
 * loaded 2,000-seat event.
 */
class RedisHoldStateReaderTest extends RedisPersistenceTestSupport {

    static final Duration WINDOW = Duration.ofSeconds(2);

    @Autowired
    RedisHoldStateReader reader;

    @AfterEach
    void removeHook() {
        reader.afterStep(step -> { });
    }

    @Value("${spring.data.redis.host}")
    String redisHost;

    @Value("${spring.data.redis.port}")
    int redisPort;

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

    // --- the paged first read (M2.2d) -----------------------------------------------------------

    @Test
    void thePagedReadReadsTheSameAsOneAtomicReadWhenNothingChanges() {
        UUID event = loadTimingEvent();   // 2,000 seats in many pages, each held
        // Keys that must not be read: this test's other event, and another prefix with the same event id.
        load();
        hold(USER, FIVE_MINUTES, SEATS.get(0), SEATS.get(1));
        RedisHoldStore other = new RedisHoldStore(redis, new RedisInventoryProperties("reader_paging_other"));
        try {
            other.loadEvent(event, SEATS, 9);
            other.hold(new HoldRequest(UUID.randomUUID(), event, USER, List.of(SEATS.get(3)), FIVE_MINUTES));
            List<String> stepsSeen = new ArrayList<>();

            Snapshot paged = readWith(stepsSeen::add, event);
            Snapshot atomic = reader.reread(event, WINDOW, paged.seatValues().keySet(), paged.holds().keySet(),
                    paged.capMembers().keySet()).orElseThrow();

            assertThat(stepsSeen.size()).as("header, seat set, scan, 80 seat pages, 20 user pages, trailer")
                    .isEqualTo(3 + 2000 / RedisHoldStateReader.SEAT_PAGE + 500 / RedisHoldStateReader.USER_PAGE + 1);
            assertThat(paged.seatIds()).hasSize(2000).isEqualTo(atomic.seatIds());
            assertThat(paged.seatValues()).hasSize(2000).isEqualTo(atomic.seatValues());
            assertThat(paged.holds()).hasSize(500).isEqualTo(atomic.holds());
            assertThat(paged.capMembers()).hasSize(500).isEqualTo(atomic.capMembers());
            assertThat(paged.perUserLimit()).isEqualTo(atomic.perUserLimit());
            assertThat(paged.unappliedEntries()).isEqualTo(atomic.unappliedEntries());
        } finally {
            TestRedis.deleteKeys(redis, "reader_paging_other");
        }
        drain();
    }

    @Test
    void aHoldNoSeatOrMemberNamesIsReadByAHoldPage() {
        load();
        UUID holdId = hold(USER, FIVE_MINUTES, SEATS.get(0));
        drain();
        // Its seat key and member are gone (drift), its hash is not: only the SCAN finds it.
        redisCommand("DEL", store.keys().seat(EVENT, SEATS.get(0)), store.keys().user(EVENT, USER));
        List<String> steps = new ArrayList<>();

        Snapshot snapshot = readWith(steps::add, EVENT);

        assertThat(steps).contains("holds");
        assertThat(snapshot.holds()).containsOnlyKeys(holdId);
        assertThat(violations()).extracting(Violation::ruleId).containsOnly("I10");
        restore(holdId);
    }

    /** Every step of the read × every write that can land between two steps. */
    static Stream<Arguments> tears() {
        return Stream.of("header", "seat set", "scan", "seats", "users", "trailer")
                .flatMap(step -> Stream.of("hold", "release", "expire").map(write -> Arguments.of(step, write)));
    }

    /**
     * A write lands right after one step of the first read, and the consumer applies it before the
     * snapshot: the first read is torn (part from before the write, part from after). Strict I10 must
     * report nothing: whatever the tear makes disagree is re-read after the snapshot.
     */
    @ParameterizedTest(name = "{1} after the {0} step")
    @MethodSource("tears")
    void aWriteBetweenTwoStepsOfTheFirstReadIsClearedByTheReRead(String step, String write) {
        load();
        UUID kept = hold(USER, FIVE_MINUTES, SEATS.get(0), SEATS.get(1));
        UUID toRelease = hold(OTHER_USER, FIVE_MINUTES, SEATS.get(2));
        UUID toExpire = hold(USERS.get(3), ONE_SECOND, SEATS.get(3));
        drain();
        AtomicInteger fired = new AtomicInteger();
        reader.afterStep(done -> {
            if (done.equals(step) && fired.getAndIncrement() == 0) {
                switch (write) {
                    case "hold" -> hold(USERS.get(2), FIVE_MINUTES, SEATS.get(5), SEATS.get(6));
                    case "release" -> releaseInRedis(toRelease);
                    case "expire" -> org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5))
                            .pollInterval(Duration.ofMillis(20)).until(() -> store.liveHold(EVENT, toExpire).isEmpty());
                    default -> throw new IllegalArgumentException(write);
                }
                drain();
            }
        });

        assertThat(violations()).as("%s after the %s step", write, step).isEmpty();
        assertThat(fired.get()).as("the write landed inside the read").isEqualTo(1);
        assertThat(store.liveHold(EVENT, kept)).isPresent();
    }

    @Test
    void realDriftIsReportedWhileOtherHoldsChangeDuringTheRead() {
        load();
        UUID drifted = hold(USER, FIVE_MINUTES, SEATS.get(0), SEATS.get(1));
        UUID toRelease = hold(OTHER_USER, FIVE_MINUTES, SEATS.get(2));
        drain();
        // Drift: Redis lost one seat key of a live hold; Postgres still has the seat held.
        String seatKey = store.keys().seat(EVENT, SEATS.get(1));
        String value = redis.opsForValue().get(seatKey);
        redisCommand("DEL", seatKey);
        AtomicInteger step = new AtomicInteger();
        reader.afterStep(done -> {
            switch (step.getAndIncrement()) {   // a different write after each of the first steps
                case 1 -> hold(USERS.get(2), FIVE_MINUTES, SEATS.get(5));
                case 2 -> releaseInRedis(toRelease);
                case 3 -> hold(USERS.get(3), FIVE_MINUTES, SEATS.get(6), SEATS.get(7));
                default -> { }
            }
            drain();
        });

        List<Violation> found = violations();

        assertThat(step.get()).as("writes landed inside the read").isGreaterThan(3);
        assertThat(found).singleElement().satisfies(v -> {
            assertThat(v.ruleId()).isEqualTo("I10");
            assertThat(v.detail()).contains("seat " + SEATS.get(1) + " is free in Redis but held by " + drifted
                    + " in Postgres");
        });
        reader.afterStep(done -> { });
        // Put the key back as hold.lua wrote it, so the after-test check sees both stores agree.
        redis.opsForValue().set(seatKey, value);
        redisCommand("PEXPIREAT", seatKey,
                Long.toString(store.liveHold(EVENT, drifted).orElseThrow().expiresAt().toEpochMilli()));
        assertThat(violations()).isEmpty();
    }

    @Test
    void aReloadDuringTheReadIsNoticedAndTheReadTakenAgain() {
        load();
        hold(USER, FIVE_MINUTES, SEATS.get(0));
        drain();
        AtomicInteger reloads = new AtomicInteger();
        reader.afterStep(done -> {
            if (done.equals("seats") && reloads.getAndIncrement() == 0) {
                wipeAndReload();
            }
        });

        Snapshot snapshot = reader.read(EVENT, WINDOW).orElseThrow();

        assertThat(reloads.get()).as("the first read saw the reload; the second didn't").isEqualTo(2);
        assertThat(snapshot.seatValues()).containsOnlyKeys(SEATS.get(0));
        reader.afterStep(done -> { });
        assertThat(violations()).isEmpty();
    }

    @Test
    void aReloadDuringBothReadsIsReportedAsCannotCheck() {
        load();
        hold(USER, FIVE_MINUTES, SEATS.get(0));
        drain();
        reader.afterStep(done -> {
            if (done.equals("seats")) {
                wipeAndReload();
            }
        });

        assertThat(violations()).singleElement().satisfies(v -> {
            assertThat(v.ruleId()).isEqualTo("I10");
            assertThat(v.detail()).startsWith("cannot check: reading Redis failed: IllegalStateException")
                    .contains("reloaded in Redis during two reads in a row");
        });
        reader.afterStep(done -> { });
        assertThat(violations()).isEmpty();
    }

    @Test
    void anEventUnloadedDuringTheReadReadsAsNotLoaded() {
        load();
        hold(USER, FIVE_MINUTES, SEATS.get(0));
        drain();
        reader.afterStep(done -> {
            if (done.equals("seats")) {
                TestRedis.deleteKeys(redis, PREFIX);
            }
        });

        assertThat(reader.read(EVENT, WINDOW)).isEmpty();
    }

    private Snapshot readWith(Consumer<String> hook, UUID event) {
        reader.afterStep(hook);
        try {
            return reader.read(event, WINDOW).orElseThrow();
        } finally {
            reader.afterStep(step -> { });
        }
    }

    /** Redis lost everything and the reconciler's R0 loaded the event again from Postgres (new epoch). */
    private void wipeAndReload() {
        long before = store.nowMillis();
        TestRedis.deleteKeys(redis, PREFIX);
        consumer.ensureGroup();
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(1)).until(() -> store.nowMillis() > before);
        load();
    }

    /** Recreates a hold's seat keys and member exactly as hold.lua wrote them (undoes test drift). */
    private void restore(UUID holdId) {
        Hold hold = reader.reread(EVENT, WINDOW, List.of(), List.of(holdId), List.of()).orElseThrow().holds().get(holdId);
        String created = Long.toString(hold.createdAt().toEpochMilli());
        String expires = Long.toString(hold.expiresAt().toEpochMilli());
        for (UUID seat : hold.seatIds()) {
            redisCommand("SET", store.keys().seat(EVENT, seat), "H:" + holdId + ":" + created, "PXAT", expires);
        }
        redisCommand("ZADD", store.keys().user(EVENT, hold.userId()), expires, holdId + ":" + hold.seatIds().size());
        redisCommand("PEXPIREAT", store.keys().user(EVENT, hold.userId()), expires);
        assertThat(violations()).isEmpty();
    }

    // --- cost ------------------------------------------------------------------------------------

    /**
     * How much {@link RedisHoldStateReader#read} blocks Redis on the demo event's size: 2,000 seats, all
     * held by 500 buyers with one 4-seat hold each (every seat key, hold hash and user set exists).
     * Two separate phases, because logging every command costs time of its own:
     * <ol>
     *   <li><b>Longest single command</b>, from Redis' own SLOWLOG with every command logged: how long one
     *       command (a script counts as one) kept Redis from serving anyone else. Server side, no
     *       network.</li>
     *   <li><b>A buyer's view</b>: a probe on its own connection sends PING in a loop; its worst round
     *       trip while reads run, against its worst round trip while Redis is idle (Docker's network
     *       jitter is in both).</li>
     * </ol>
     * Printed (D-022 M2.2d records the numbers); the bounds are loose so CI noise can't fail the test.
     */
    @Test
    @SuppressWarnings("unchecked")
    void howLongTheFirstReadBlocksRedisOnALoaded2000SeatEvent() throws Exception {
        UUID event = loadTimingEvent();
        long keysInRedis = redis.execute((RedisCallback<Long>) connection -> connection.serverCommands().dbSize());
        assertThat(reader.read(event, WINDOW)).isPresent();   // loads the scripts: not measured

        // Phase 1: SLOWLOG with every command logged.
        String previousThreshold = config("slowlog-log-slower-than", "10000");
        String previousLength = config("slowlog-max-len", "128");
        List<Long> longestPerRead = new ArrayList<>();
        List<Long> readMillis = new ArrayList<>();
        String longestCommand = "";
        long longestSeen = -1;
        try {
            setConfig("slowlog-max-len", "100000");
            setConfig("slowlog-log-slower-than", "0");
            for (int run = 0; run < 10; run++) {
                slowlog(server -> server.slowlogReset());
                long start = System.nanoTime();
                Snapshot snapshot = reader.read(event, WINDOW).orElseThrow();
                readMillis.add((System.nanoTime() - start) / 1_000_000);
                assertThat(snapshot.seatValues()).hasSize(2000);
                assertThat(snapshot.holds()).hasSize(500);
                assertThat(snapshot.capMembers()).hasSize(500);
                long longest = 0;
                // Each entry: id, unix time, execution time in microseconds, the command's arguments, ...
                for (Object entry : (List<Object>) slowlog(server -> server.slowlogGet(100000))) {
                    List<Object> fields = (List<Object>) entry;
                    long micros = (Long) fields.get(2);
                    List<String> args = ((List<Object>) fields.get(3)).stream().map(RedisHoldStateReaderTest::text).toList();
                    if (args.get(0).toUpperCase().startsWith("SLOWLOG")) {
                        continue;
                    }
                    longest = Math.max(longest, micros);
                    if (micros > longestSeen) {
                        longestSeen = micros;
                        longestCommand = describe(args);
                    }
                }
                longestPerRead.add(longest);
            }
        } finally {
            setConfig("slowlog-log-slower-than", previousThreshold);
            setConfig("slowlog-max-len", previousLength);
            slowlog(server -> server.slowlogReset());
        }

        // Phase 2: the PING probe, SLOWLOG back to normal.
        Probe probe = new Probe(redisHost, redisPort);
        List<long[]> idle = new ArrayList<>();
        List<long[]> reads = new ArrayList<>();
        try {
            probe.start();
            long idleStart = System.nanoTime();
            org.awaitility.Awaitility.await().pollDelay(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(5))
                    .until(() -> true);
            idle.add(new long[] {idleStart, System.nanoTime()});
            for (int run = 0; run < 10; run++) {
                long start = System.nanoTime();
                reader.read(event, WINDOW).orElseThrow();
                reads.add(new long[] {start, System.nanoTime()});
            }
        } finally {
            probe.stop();
        }

        List<Long> sortedLongest = longestPerRead.stream().sorted().toList();
        List<Long> sortedRead = readMillis.stream().sorted().toList();
        System.out.println("[I10 first read] 2000 seats, 500 holds, 500 users, " + keysInRedis + " keys in the Redis db."
                + " Longest single command per read (us, 10 reads): " + sortedLongest + ", max " + longestSeen
                + " us (" + longestCommand + "). Read time median " + sortedRead.get(sortedRead.size() / 2)
                + " ms (SLOWLOG on). PING probe round trips: idle " + probe.summary(idle)
                + "; during reads " + probe.summary(reads));

        assertThat(sortedLongest.get(sortedLongest.size() / 2)).as("median longest command, us").isLessThan(500_000);
        assertThat(probe.count(reads)).as("the probe ran while the reads did").isPositive();
        assertThat(invariants.check(event, Duration.ZERO)).as("loaded from Postgres: both stores agree").isEmpty();
    }

    /**
     * The demo event's size, loaded into Redis from Postgres: 2,000 seats, all held by 500 buyers with
     * one 4-seat hold each, so every seat key, hold hash and user set exists.
     */
    private UUID loadTimingEvent() {
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
        return event;
    }

    /**
     * Sends PING in a loop on its own connection (not the application's shared one, where it would queue
     * behind the reader's own commands) and records each round trip as {start, end} in nanoseconds.
     */
    private static final class Probe {
        private final io.lettuce.core.RedisClient client;
        private final List<long[]> pings = java.util.Collections.synchronizedList(new ArrayList<>());
        private volatile boolean running;
        private Thread thread;

        Probe(String host, int port) {
            this.client = io.lettuce.core.RedisClient.create(io.lettuce.core.RedisURI.create(host, port));
        }

        void start() {
            var connection = client.connect();
            var commands = connection.sync();
            commands.ping();   // connected and warm before anything is measured
            running = true;
            thread = new Thread(() -> {
                try (connection) {
                    while (running) {
                        long start = System.nanoTime();
                        commands.ping();
                        pings.add(new long[] {start, System.nanoTime()});
                    }
                }
            }, "ping-probe");
            thread.start();
        }

        void stop() throws InterruptedException {
            running = false;
            if (thread != null) {
                thread.join(5_000);
            }
            client.shutdown();
        }

        long count(List<long[]> windows) {
            return overlapping(windows).count();
        }

        /** Worst, 99th percentile and how many took over 10 ms: one worst value alone is mostly Docker jitter. */
        String summary(List<long[]> windows) {
            List<Long> micros = overlapping(windows).map(p -> (p[1] - p[0]) / 1_000).sorted().toList();
            if (micros.isEmpty()) {
                return "no pings";
            }
            long p99 = micros.get(Math.min(micros.size() - 1, (int) Math.ceil(micros.size() * 0.99) - 1));
            long over10ms = micros.stream().filter(m -> m > 10_000).count();
            return micros.size() + " pings, worst " + micros.get(micros.size() - 1) + " us, p99 " + p99 + " us, "
                    + over10ms + " over 10 ms";
        }

        private java.util.stream.Stream<long[]> overlapping(List<long[]> windows) {
            List<long[]> copy;
            synchronized (pings) {
                copy = new ArrayList<>(pings);
            }
            return copy.stream().filter(p -> windows.stream().anyMatch(w -> p[0] < w[1] && p[1] > w[0]));
        }
    }

    /** A SLOWLOG command line, short: for a state.lua call, its mode and how many seats, holds, users. */
    private static String describe(List<String> args) {
        String command = args.get(0).toUpperCase();
        if (!command.startsWith("EVAL") || args.size() < 12) {
            return command;
        }
        // EVALSHA sha 3 meta seats stream | mode prefix group event window | counts and ids
        StringBuilder text = new StringBuilder(command + " " + args.get(6));
        int pos = 11;
        for (String kind : List.of(" seats=", " holds=", " users=")) {
            if (pos >= args.size() || !args.get(pos).matches("\\d+")) {
                return text + " (arguments truncated by SLOWLOG)";
            }
            int count = Integer.parseInt(args.get(pos));
            text.append(kind).append(count);
            pos += count + 1;
        }
        return text.toString();
    }

    private String config(String name, String fallback) {
        java.util.Properties config = redis.execute((RedisCallback<java.util.Properties>) c -> c.serverCommands()
                .getConfig(name));
        return config == null ? fallback : config.getProperty(name, fallback);
    }

    private void setConfig(String name, String value) {
        redis.execute((RedisCallback<Object>) c -> {
            c.serverCommands().setConfig(name, value);
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
