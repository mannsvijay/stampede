package com.stampede.control.invariants;

import static org.assertj.core.api.Assertions.assertThat;

import com.stampede.inventory.api.RedisHoldState;
import com.stampede.inventory.api.RedisHoldState.CapMember;
import com.stampede.inventory.api.RedisHoldState.Hold;
import com.stampede.inventory.api.RedisHoldState.SeatValue;
import com.stampede.inventory.api.RedisHoldState.Snapshot;
import com.stampede.inventory.api.RedisHoldState.UnappliedEntry;
import com.stampede.testsupport.TestDatabases;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Proves every way invariant 5 (I10, {@link RedisConsistencyCheck}, D-022 M2.2c-2) compares Redis with
 * Postgres can fire, and that its tolerance excuses exactly what it should: nothing when strict; at
 * runtime only consumer lag under 2 s and a sale finishing within the grace period. The read-order race
 * (a change between the Redis read and the Postgres snapshot) is cleared by the re-read.
 * <p>
 * Redis is a stub {@link RedisHoldState}, so each Redis state is exact and the clock is fixed. The real
 * reader and scripts are tested in {@code RedisHoldStateReaderTest}. Postgres is real, in this class's
 * own database, inside {@code @JdbcTest}'s rolled-back test transaction (the checker joins it).
 * <p>
 * Baseline, one REDIS_FIRST event with seats S1-S4, at Redis time {@link #T}: S2 held by alice's
 * ACTIVE hold H1 (created T - 1 min, expires T + 4 min); S3 sold to bob's order O1 (CONVERTED hold
 * H2). Redis agrees exactly. Only I10's lines are asserted: the minimal order fixture breaks SQL rules
 * (no payment, no ticket) that InvariantCheckerTest covers.
 */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class RedisConsistencyCheckTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabases.register(registry, "redis_consistency_check");
    }

    static final Duration STRICT = Duration.ZERO;
    static final Duration GRACE = Duration.ofSeconds(30);

    /** Redis' clock in every snapshot. Postgres stores Redis' times for REDIS_FIRST holds. */
    static final Instant T = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    static final Instant CREATED = T.minus(Duration.ofMinutes(1));
    static final Instant EXPIRES = T.plus(Duration.ofMinutes(4));

    static final UUID ALICE = UUID.fromString("00000000-0000-4000-8000-0000000000a1");
    static final UUID BOB = UUID.fromString("00000000-0000-4000-8000-0000000000b0");
    static final UUID VENUE = UUID.fromString("00000000-0000-4000-8000-000000000001");
    static final UUID SECTION = UUID.fromString("00000000-0000-4000-8000-000000000002");
    static final UUID EVENT = UUID.fromString("00000000-0000-4000-8000-00000000000e");
    static final UUID TIER = UUID.fromString("00000000-0000-4000-8000-0000000000c1");
    static final UUID S1 = UUID.fromString("10000000-0000-4000-8000-000000000001");
    static final UUID S2 = UUID.fromString("20000000-0000-4000-8000-000000000002");
    static final UUID S3 = UUID.fromString("30000000-0000-4000-8000-000000000003");
    static final UUID S4 = UUID.fromString("40000000-0000-4000-8000-000000000004");
    static final UUID H1 = UUID.fromString("00000000-0000-4000-8000-0000000001a1");
    static final UUID H2 = UUID.fromString("00000000-0000-4000-8000-0000000001a2");
    static final UUID H3 = UUID.fromString("00000000-0000-4000-8000-0000000001a3");
    static final UUID O1 = UUID.fromString("00000000-0000-4000-8000-0000000002b1");

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager transactions;

    StubRedis redis;
    InvariantChecker checker;

    @BeforeEach
    void insertBaseline() {
        redis = new StubRedis(baselineRedis());
        checker = new InvariantChecker(jdbc, transactions, SqlRules.ALL, new RedisConsistencyCheck(redis));

        jdbc.sql("TRUNCATE users, venues CASCADE").update();
        jdbc.sql("INSERT INTO users (id, email, password_hash) VALUES (?, 'alice@example.test', 'x')").param(ALICE).update();
        jdbc.sql("INSERT INTO users (id, email, password_hash) VALUES (?, 'bob@example.test', 'x')").param(BOB).update();
        jdbc.sql("INSERT INTO venues (id, name, layout_json) VALUES (?, 'Venue', '{}')").param(VENUE).update();
        jdbc.sql("INSERT INTO sections (id, venue_id, code, default_tier) VALUES (?, ?, 'A', 'GOLD')")
                .params(SECTION, VENUE).update();
        int number = 1;
        for (UUID seat : List.of(S1, S2, S3, S4)) {
            jdbc.sql("INSERT INTO seats (id, section_id, row_label, number, x, y) VALUES (?, ?, 'A', ?, 0, 0)")
                    .params(seat, SECTION, number++).update();
        }
        jdbc.sql("""
                INSERT INTO events (id, venue_id, title, starts_at, drop_at, status, per_user_limit, strategy)
                VALUES (?, ?, 'Event', now() + interval '30 days', now() - interval '1 hour', 'LIVE', 4, 'REDIS_FIRST')
                """).params(EVENT, VENUE).update();
        jdbc.sql("INSERT INTO price_tiers (id, event_id, name, price_paise) VALUES (?, ?, 'GOLD', 150000)")
                .params(TIER, EVENT).update();
        for (UUID seat : List.of(S1, S2, S3, S4)) {
            jdbc.sql("INSERT INTO event_seats (event_id, seat_id, tier_id, status) VALUES (?, ?, ?, 'AVAILABLE')")
                    .params(EVENT, seat, TIER).update();
        }
        hold(H1, ALICE, "ACTIVE", CREATED, EXPIRES, S2);
        held(S2, H1);
        hold(H2, BOB, "CONVERTED", CREATED, EXPIRES, S3);
        order(O1, BOB, H2, "PAID");
        sold(S3, O1);
    }

    /** Redis exactly as Postgres has the baseline. */
    static Snapshot baselineRedis() {
        return new Snapshot(T, 4, Set.of(S1, S2, S3, S4),
                Map.of(S2, new SeatValue.Held(H1), S3, new SeatValue.Sold(O1)),
                Map.of(H1, new Hold(H1, ALICE, List.of(S2), CREATED, EXPIRES, false)),
                Map.of(ALICE, List.of(new CapMember(H1, 1, EXPIRES)), BOB, List.of(new CapMember(H2, 1, null))),
                List.of());
    }

    @Test
    void theBaselineAgrees() {
        assertI10(STRICT);
        assertI10(GRACE);
    }

    @Test
    void notRedisFirstEventsNeverReadRedis() {
        jdbc.sql("UPDATE events SET strategy = 'PESSIMISTIC' WHERE id = ?").param(EVENT).update();
        redis.first = withLimit(99).apply(baselineRedis());
        assertI10(STRICT);
        assertThat(redis.reads).isZero();
    }

    @Test
    void anEventNotLoadedInRedisHasNothingToCompare() {
        redis.first = null;   // read() returns empty: not loaded
        assertI10(STRICT);
    }

    @Test
    void aFailedRedisReadIsReportedNotPassed() {
        redis.failure = new IllegalStateException("connection refused");
        assertThat(i10(STRICT)).singleElement().asString()
                .contains("cannot check: reading Redis failed: IllegalStateException: connection refused");
    }

    // --- holds -----------------------------------------------------------------------------------

    @Test
    void aHoldOnlyRedisHasIsReportedUnlessAYoungUnappliedEntryExplainsIt() {
        // H3 was taken in Redis; its HELD entry hasn't reached Postgres yet.
        UnaryOperator<Snapshot> h3InRedis = withHold(new Hold(H3, ALICE, List.of(S1), T, EXPIRES, false))
                .andThen(withSeat(S1, new SeatValue.Held(H3)))::apply;
        UnaryOperator<Snapshot> h3Counted = s -> withMembers(ALICE,
                new CapMember(H1, 1, EXPIRES), new CapMember(H3, 1, EXPIRES)).apply(h3InRedis.apply(s));

        redis.first = h3Counted.apply(baselineRedis());
        List<String> strict = i10(STRICT);
        assertThat(strict).hasSize(3);  // the hold, its seat, alice's cap
        assertThat(strict).anyMatch(v -> v.contains("hold " + H3 + " is live in Redis"))
                .anyMatch(v -> v.contains("seat " + S1 + " is held by " + H3 + " in Redis but free in Postgres"))
                .anyMatch(v -> v.contains("user " + ALICE + "'s cap counts"));
        assertThat(i10(GRACE)).as("no entry: the consumer applied it, or it never existed").hasSize(3);

        redis.first = withEntries(new UnappliedEntry(H3, "HELD", T.minusMillis(100))).apply(h3Counted.apply(baselineRedis()));
        assertThat(i10(STRICT)).as("strict: lag is not excused").hasSize(3)
                .allMatch(v -> v.contains("(HELD entry for " + H3 + " unapplied, 100 ms old)"));
        assertI10(GRACE);

        redis.first = withEntries(new UnappliedEntry(H3, "HELD", T.minusMillis(2_000))).apply(h3Counted.apply(baselineRedis()));
        assertThat(i10(GRACE)).as("2 s old: past the window").hasSize(3);
    }

    @Test
    void aHoldOnlyPostgresHasIsReportedUnlessItsReleaseIsStillInTheStream() {
        // Redis released H1; the RELEASED entry hasn't reached Postgres.
        UnaryOperator<Snapshot> released = s -> withoutHold(H1).andThen(withSeat(S2, null))
                .andThen(withMembers(ALICE)).apply(s);
        redis.first = released.apply(baselineRedis());
        assertThat(i10(STRICT)).hasSize(3)
                .anyMatch(v -> v.contains("hold " + H1 + " is ACTIVE in Postgres until " + EXPIRES + " but Redis doesn't have it"));

        redis.first = withEntries(new UnappliedEntry(H1, "RELEASED", T.minusMillis(5))).apply(released.apply(baselineRedis()));
        assertI10(GRACE);
        assertThat(i10(STRICT)).hasSize(3);
    }

    @Test
    void aHoldWhoseFieldsDifferIsReported() {
        redis.first = withHold(new Hold(H1, ALICE, List.of(S2), CREATED, EXPIRES.plusSeconds(1), false))
                .andThen(withMembers(ALICE, new CapMember(H1, 1, EXPIRES.plusSeconds(1)))).apply(baselineRedis());
        assertThat(i10(STRICT)).singleElement().asString()
                .contains("hold " + H1 + " differs (Redis vs Postgres): expires " + EXPIRES.plusSeconds(1) + " vs " + EXPIRES);
    }

    @Test
    void expiryIsComparedByTimeNotByStatus() {
        // Redis' TTL ran out; the sweeper hasn't closed the hold yet (still ACTIVE, expired 1 min ago).
        jdbc.sql("UPDATE holds SET created_at = ?, expires_at = ? WHERE id = ?")
                .params(T.minus(Duration.ofMinutes(6)).atOffset(ZoneOffset.UTC),
                        T.minus(Duration.ofMinutes(1)).atOffset(ZoneOffset.UTC), H1).update();
        redis.first = withoutHold(H1).andThen(withSeat(S2, null)).andThen(withMembers(ALICE)).apply(baselineRedis());
        assertI10(STRICT);

        // The same, with a sale that pinned the hold and crashed: Redis still has the hash and seat key,
        // past their expiry. By time, both stores say "expired".
        redis.first = withHold(new Hold(H1, ALICE, List.of(S2), CREATED, T.minus(Duration.ofMinutes(1)), true))
                .andThen(withMembers(ALICE, new CapMember(H1, 1, null))).apply(baselineRedis());
        assertI10(STRICT);
    }

    // --- seats -----------------------------------------------------------------------------------

    @Test
    void aSoldMarkerOnASeatPostgresHasNotSoldIsReportedEvenAtRuntime() {
        UUID otherOrder = UUID.randomUUID();
        redis.first = withSeat(S4, new SeatValue.Sold(otherOrder)).apply(baselineRedis());
        assertThat(i10(STRICT)).singleElement().asString()
                .contains("seat " + S4 + " is sold to " + otherOrder + " in Redis but free in Postgres");
        assertThat(i10(GRACE)).as("no hold involved: nothing can excuse it").hasSize(1);
    }

    @Test
    void aSeatKeyNamingAHoldRedisDoesNotHaveIsReported() {
        UUID ghost = UUID.randomUUID();
        redis.first = withSeat(S1, new SeatValue.Held(ghost)).apply(baselineRedis());
        assertThat(i10(STRICT)).singleElement().asString()
                .contains("seat " + S1 + " is named by a missing hold " + ghost + " in Redis but free in Postgres");
    }

    @Test
    void anUnreadableSeatValueIsReported() {
        redis.first = withSeat(S1, new SeatValue.Unreadable("X:junk")).apply(baselineRedis());
        assertThat(i10(STRICT)).singleElement().asString().contains("unreadable 'X:junk'");
    }

    // --- cap, limit, seat set --------------------------------------------------------------------

    @Test
    void aCapMemberPostgresDoesNotHaveIsReported() {
        // A member left behind (its hold and seat keys gone): Redis still counts it until its score passes.
        UUID orphan = UUID.fromString("f0000000-0000-4000-8000-0000000001ff");  // sorts after H1 in the message
        redis.first = withMembers(ALICE, new CapMember(H1, 1, EXPIRES), new CapMember(orphan, 2, EXPIRES))
                .apply(baselineRedis());
        assertThat(i10(STRICT)).singleElement().asString()
                .contains("user " + ALICE + "'s cap counts [" + H1 + ":1, " + orphan + ":2] in Redis but [" + H1
                        + ":1] in Postgres");
    }

    @Test
    void aBoughtSeatMissingFromTheCapIsReported() {
        redis.first = withMembers(BOB).apply(baselineRedis());
        assertThat(i10(STRICT)).singleElement().asString()
                .contains("user " + BOB + "'s cap counts [] in Redis but [" + H2 + ":1] in Postgres");
    }

    @Test
    void anExpiredCapMemberDoesNotCount() {
        redis.first = withMembers(ALICE, new CapMember(H1, 1, EXPIRES), new CapMember(H3, 1, T.minusMillis(1)))
                .apply(baselineRedis());
        assertI10(STRICT);
    }

    @Test
    void aDifferentLimitIsReported() {
        redis.first = withLimit(6).apply(baselineRedis());
        assertThat(i10(STRICT)).singleElement().asString()
                .contains("per-user limit is 6 in Redis but 4 in Postgres");
    }

    @Test
    void aDifferentSeatSetIsReported() {
        redis.first = withSeatIds(Set.of(S1, S2, S3)).apply(baselineRedis());
        assertThat(i10(STRICT)).hasSize(2)
                .anyMatch(v -> v.contains("seat set differs: only in Redis [], only in Postgres [" + S4 + "]"))
                .anyMatch(v -> v.contains("seat " + S4 + " is missing in Redis but free in Postgres"));
    }

    // --- the read-order race and the re-read -----------------------------------------------------

    @Test
    void aReleaseBetweenTheRedisReadAndTheSnapshotIsClearedByTheReRead() {
        // Redis was read while H1 was live; then the strategy released it (release.lua, then the Postgres
        // commit), so the snapshot sees RELEASED. Redis read again: H1 is gone there too.
        jdbc.sql("UPDATE event_seats SET status = 'AVAILABLE', hold_id = NULL WHERE event_id = ? AND seat_id = ?")
                .params(EVENT, S2).update();
        jdbc.sql("UPDATE holds SET status = 'RELEASED' WHERE id = ?").param(H1).update();
        redis.again = withoutHold(H1).andThen(withSeat(S2, null)).andThen(withMembers(ALICE))
                .andThen(withNow(T.plusMillis(3))).apply(baselineRedis());

        assertI10(STRICT);
        assertThat(redis.rereadSeats).as("only the disagreeing keys are re-read").containsExactly(S2);
        assertThat(redis.rereadHolds).containsExactly(H1);
        assertThat(redis.rereadUsers).containsExactly(ALICE);
    }

    @Test
    void whatStillDisagreesAfterTheReReadIsReported() {
        jdbc.sql("UPDATE event_seats SET status = 'AVAILABLE', hold_id = NULL WHERE event_id = ? AND seat_id = ?")
                .params(EVENT, S2).update();
        jdbc.sql("UPDATE holds SET status = 'RELEASED' WHERE id = ?").param(H1).update();
        // redis.again defaults to the first read: Redis still has H1.
        assertThat(i10(STRICT)).hasSize(3)
                .anyMatch(v -> v.contains("hold " + H1 + " is live in Redis") && v.contains("Postgres has no ACTIVE or CONVERTED row"));
        assertThat(redis.rereads).isEqualTo(1);
    }

    @Test
    void noReReadWhenTheFirstReadAgrees() {
        assertI10(STRICT);
        assertThat(redis.rereads).isZero();
    }

    // --- a sale between its Postgres commit and sold.lua (decision 2, option B) -----------------

    @Test
    void aPinnedHoldThatPostgresAlreadySoldIsExcusedOnlyWhileItsOrderIsYoung() {
        // Step 2 committed (seat SOLD, hold CONVERTED, order created now); sold.lua not yet run, so Redis
        // still has the pinned hold, its seat key and the +inf member.
        UUID order = UUID.randomUUID();
        jdbc.sql("UPDATE holds SET status = 'CONVERTED' WHERE id = ?").param(H1).update();
        order(order, ALICE, H1, "PENDING");
        sold(S2, order);
        redis.first = withHold(new Hold(H1, ALICE, List.of(S2), CREATED, EXPIRES, true))
                .andThen(withMembers(ALICE, new CapMember(H1, 1, null))).apply(baselineRedis());

        assertThat(i10(STRICT)).as("strict: always reported").hasSize(2)
                .anyMatch(v -> v.contains("hold " + H1 + " is live in Redis") && v.contains("pinned")
                        && v.contains("Postgres has CONVERTED"))
                .anyMatch(v -> v.contains("seat " + S2 + " is held by " + H1 + " in Redis but sold to " + order));
        assertI10(GRACE);

        jdbc.sql("UPDATE orders SET created_at = now() - interval '1 hour' WHERE id = ?").param(order).update();
        assertThat(i10(GRACE)).as("past the grace: sold.lua and R4 both failed to finish it").hasSize(2);
    }

    @Test
    void aPinnedHoldPastItsExpiryThatPostgresAlreadySoldIsExcusedOnlyWhileItsOrderIsYoung() {
        // Review finding L1: as above, but the sale committed after the hold's expiry (or sold.lua failed
        // and the time ran out since). By time the seat is "free" in Redis, yet its key still names the
        // pinned hold (hold.lua treats it as taken), so the difference is that sale finishing too.
        Instant expired = T.minus(Duration.ofSeconds(1));
        UUID order = UUID.randomUUID();
        jdbc.sql("UPDATE holds SET status = 'CONVERTED', expires_at = ? WHERE id = ?")
                .params(expired.atOffset(ZoneOffset.UTC), H1).update();
        order(order, ALICE, H1, "PENDING");
        sold(S2, order);
        redis.first = withHold(new Hold(H1, ALICE, List.of(S2), CREATED, expired, true))
                .andThen(withMembers(ALICE, new CapMember(H1, 1, null))).apply(baselineRedis());

        assertThat(i10(STRICT)).as("strict: always reported").hasSize(2)
                .anyMatch(v -> v.contains("seat " + S2 + " is free in Redis but sold to " + order));
        assertI10(GRACE);

        jdbc.sql("UPDATE orders SET created_at = now() - interval '1 hour' WHERE id = ?").param(order).update();
        assertThat(i10(GRACE)).as("past the grace: sold.lua and R4 both failed to finish it").hasSize(2);
    }

    @Test
    void aPinnedHoldPastItsExpiryWhoseSaleRolledBackAgreesWithPostgres() {
        // The other way a pinned hold outlives its expiry: the Postgres sale rolled back and no
        // compensation ran, so the sweeper will free it. By time it is over on both sides: nothing to report.
        Instant expired = T.minus(Duration.ofSeconds(1));
        jdbc.sql("UPDATE holds SET expires_at = ? WHERE id = ?").params(expired.atOffset(ZoneOffset.UTC), H1).update();
        redis.first = withHold(new Hold(H1, ALICE, List.of(S2), CREATED, expired, true))
                .andThen(withMembers(ALICE, new CapMember(H1, 1, null))).apply(baselineRedis());

        assertI10(STRICT);
    }

    @Test
    void anUnpinnedHoldThatPostgresSoldIsNotASaleFinishing() {
        UUID order = UUID.randomUUID();
        jdbc.sql("UPDATE holds SET status = 'CONVERTED' WHERE id = ?").param(H1).update();
        order(order, ALICE, H1, "PENDING");
        sold(S2, order);
        redis.first = withMembers(ALICE, new CapMember(H1, 1, null)).apply(baselineRedis());  // H1 not pinned
        assertThat(i10(GRACE)).hasSize(2);
    }

    // --- helpers ---------------------------------------------------------------------------------

    private List<String> i10(Duration grace) {
        return checker.check(EVENT, grace).stream()
                .filter(v -> v.ruleId().equals("I10"))
                .map(Violation::toString)
                .toList();
    }

    private void assertI10(Duration grace) {
        assertThat(i10(grace)).as("grace %s", grace).isEmpty();
    }

    private void hold(UUID id, UUID user, String status, Instant created, Instant expires, UUID... seats) {
        jdbc.sql("""
                INSERT INTO holds (id, event_id, user_id, seat_ids, created_at, expires_at, status)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """).params(id, EVENT, user, seats, created.atOffset(ZoneOffset.UTC), expires.atOffset(ZoneOffset.UTC),
                status).update();
    }

    private void held(UUID seat, UUID holdId) {
        jdbc.sql("UPDATE event_seats SET status = 'HELD', hold_id = ?, order_id = NULL WHERE event_id = ? AND seat_id = ?")
                .params(holdId, EVENT, seat).update();
    }

    private void sold(UUID seat, UUID orderId) {
        jdbc.sql("UPDATE event_seats SET status = 'SOLD', hold_id = NULL, order_id = ? WHERE event_id = ? AND seat_id = ?")
                .params(orderId, EVENT, seat).update();
    }

    private void order(UUID id, UUID user, UUID holdId, String status) {
        jdbc.sql("""
                INSERT INTO orders (id, user_id, event_id, status, total_paise, saga_state, hold_id)
                VALUES (?, ?, ?, ?, 150000, 'SEATS_SOLD', ?)
                """).params(id, user, EVENT, status, holdId).update();
    }

    // Snapshot edits, applied to a copy of the baseline.

    static UnaryOperator<Snapshot> withHold(Hold hold) {
        return s -> {
            Map<UUID, Hold> holds = new HashMap<>(s.holds());
            holds.put(hold.holdId(), hold);
            return new Snapshot(s.now(), s.perUserLimit(), s.seatIds(), s.seatValues(), holds, s.capMembers(),
                    s.unappliedEntries());
        };
    }

    static UnaryOperator<Snapshot> withoutHold(UUID holdId) {
        return s -> {
            Map<UUID, Hold> holds = new HashMap<>(s.holds());
            holds.remove(holdId);
            return new Snapshot(s.now(), s.perUserLimit(), s.seatIds(), s.seatValues(), holds, s.capMembers(),
                    s.unappliedEntries());
        };
    }

    /** {@code value} null = the seat key doesn't exist. */
    static UnaryOperator<Snapshot> withSeat(UUID seat, SeatValue value) {
        return s -> {
            Map<UUID, SeatValue> seats = new HashMap<>(s.seatValues());
            if (value == null) {
                seats.remove(seat);
            } else {
                seats.put(seat, value);
            }
            return new Snapshot(s.now(), s.perUserLimit(), s.seatIds(), seats, s.holds(), s.capMembers(),
                    s.unappliedEntries());
        };
    }

    /** No members = the user's key doesn't exist. */
    static UnaryOperator<Snapshot> withMembers(UUID user, CapMember... members) {
        return s -> {
            Map<UUID, List<CapMember>> caps = new HashMap<>(s.capMembers());
            if (members.length == 0) {
                caps.remove(user);
            } else {
                caps.put(user, List.of(members));
            }
            return new Snapshot(s.now(), s.perUserLimit(), s.seatIds(), s.seatValues(), s.holds(), caps,
                    s.unappliedEntries());
        };
    }

    static UnaryOperator<Snapshot> withEntries(UnappliedEntry... entries) {
        return s -> new Snapshot(s.now(), s.perUserLimit(), s.seatIds(), s.seatValues(), s.holds(), s.capMembers(),
                List.of(entries));
    }

    static UnaryOperator<Snapshot> withLimit(int limit) {
        return s -> new Snapshot(s.now(), limit, s.seatIds(), s.seatValues(), s.holds(), s.capMembers(),
                s.unappliedEntries());
    }

    static UnaryOperator<Snapshot> withSeatIds(Set<UUID> seatIds) {
        return s -> new Snapshot(s.now(), s.perUserLimit(), seatIds, s.seatValues(), s.holds(), s.capMembers(),
                s.unappliedEntries());
    }

    static UnaryOperator<Snapshot> withNow(Instant now) {
        return s -> new Snapshot(now, s.perUserLimit(), s.seatIds(), s.seatValues(), s.holds(), s.capMembers(),
                s.unappliedEntries());
    }

    /**
     * Redis as the test sets it: {@code first} for the read (null = not loaded), {@code again} for the
     * re-read (null = the same as {@code first}, i.e. Redis didn't change). Records what was re-read.
     */
    static final class StubRedis implements RedisHoldState {
        Snapshot first;
        Snapshot again;
        RuntimeException failure;
        int reads;
        int rereads;
        List<UUID> rereadSeats = new ArrayList<>();
        List<UUID> rereadHolds = new ArrayList<>();
        List<UUID> rereadUsers = new ArrayList<>();

        StubRedis(Snapshot first) {
            this.first = first;
        }

        @Override
        public Optional<Snapshot> read(UUID eventId, Duration entriesYoungerThan) {
            reads++;
            assertThat(entriesYoungerThan).isEqualTo(Duration.ofSeconds(2));
            if (failure != null) {
                throw failure;
            }
            return Optional.ofNullable(first);
        }

        @Override
        public Optional<Snapshot> reread(UUID eventId, Duration entriesYoungerThan, Collection<UUID> seatIds,
                                         Collection<UUID> holdIds, Collection<UUID> userIds) {
            rereads++;
            rereadSeats = List.copyOf(seatIds);
            rereadHolds = List.copyOf(holdIds);
            rereadUsers = List.copyOf(userIds);
            return Optional.ofNullable(again != null ? again : first);
        }
    }
}
