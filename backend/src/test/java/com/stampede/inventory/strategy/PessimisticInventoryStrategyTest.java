package com.stampede.inventory.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stampede.inventory.seat.SeatStatus;
import com.stampede.inventory.strategy.HoldOutcome.Held;
import com.stampede.inventory.strategy.HoldOutcome.Rejected;
import com.stampede.inventory.strategy.ReleaseOutcome.NotActive;
import com.stampede.inventory.strategy.ReleaseOutcome.NotFound;
import com.stampede.inventory.strategy.ReleaseOutcome.Released;
import com.stampede.inventory.strategy.ReleaseOutcome.NotExpired;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The pessimistic strategy against a real Postgres (M1.2, D-015).
 * <p>
 * Runs WITHOUT the usual test transaction ({@code NOT_SUPPORTED}), like SeedLoaderTest: the strategy's
 * own transaction must really commit or roll back, and the race tests need two real transactions on
 * two connections. Tables are emptied and the fixture inserted again before each test.
 * <p>
 * Release tests create their holds with plain SQL ({@link #holdDirectly}), so they don't depend on
 * {@code hold()} being correct.
 */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import(PessimisticInventoryStrategy.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PessimisticInventoryStrategyTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    static final ExecutorService THREADS = Executors.newFixedThreadPool(2);

    static final UUID USER = UUID.fromString("00000000-0000-4000-8000-0000000000a1");
    static final UUID VENUE = UUID.fromString("00000000-0000-4000-8000-000000000001");
    static final UUID SECTION = UUID.fromString("00000000-0000-4000-8000-000000000002");
    static final UUID EVENT_A = UUID.fromString("00000000-0000-4000-8000-00000000000a");
    static final UUID EVENT_B = UUID.fromString("00000000-0000-4000-8000-00000000000b");
    static final UUID TIER_A = UUID.fromString("00000000-0000-4000-8000-0000000000ca");
    static final UUID TIER_B = UUID.fromString("00000000-0000-4000-8000-0000000000cb");

    // Seat ids chosen so Java and Postgres sort them differently: UUID.compareTo compares the two
    // halves as SIGNED longs, so SEAT_2 (first hex digit 9, the sign bit set) sorts BEFORE SEAT_1 in
    // Java, while Postgres compares bytes unsigned and puts SEAT_1 first. See D-015.
    static final UUID SEAT_1 = UUID.fromString("10000000-0000-4000-8000-000000000001");
    static final UUID SEAT_2 = UUID.fromString("90000000-0000-4000-8000-000000000002");
    static final UUID SEAT_3 = UUID.fromString("30000000-0000-4000-8000-000000000003");
    static final UUID SEAT_4 = UUID.fromString("40000000-0000-4000-8000-000000000004");
    /** A seat of event B only. */
    static final UUID SEAT_B = UUID.fromString("50000000-0000-4000-8000-000000000005");

    static final Duration FIVE_MINUTES = Duration.ofMinutes(5);

    @Autowired
    PessimisticInventoryStrategy strategy;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    DataSource dataSource;

    @AfterAll
    static void stopThreads() {
        THREADS.shutdownNow();
    }

    @BeforeEach
    void insertFixture() {
        jdbc.sql("TRUNCATE users, venues CASCADE").update();
        jdbc.sql("INSERT INTO users (id, email, password_hash) VALUES (?, 'buyer@example.test', 'x')")
                .param(USER).update();
        jdbc.sql("INSERT INTO venues (id, name, layout_json) VALUES (?, 'Test venue', '{}')").param(VENUE).update();
        jdbc.sql("INSERT INTO sections (id, venue_id, code, default_tier) VALUES (?, ?, 'A', 'GOLD')")
                .params(SECTION, VENUE).update();
        int number = 1;
        for (UUID seat : List.of(SEAT_1, SEAT_2, SEAT_3, SEAT_4, SEAT_B)) {
            jdbc.sql("INSERT INTO seats (id, section_id, row_label, number, x, y) VALUES (?, ?, 'A', ?, 0, 0)")
                    .params(seat, SECTION, number++).update();
        }
        // SEAT_2 is inserted first ON PURPOSE: a scan in physical order then meets SEAT_2 before SEAT_1,
        // the opposite of seat_id order. lockOrderIsPostgresSeatIdOrder relies on that to tell
        // "ORDER BY seat_id FOR UPDATE" apart from "whatever order the scan returns".
        insertEvent(EVENT_A, TIER_A, SEAT_2, SEAT_1, SEAT_3, SEAT_4);
        insertEvent(EVENT_B, TIER_B, SEAT_B);
    }

    private void insertEvent(UUID event, UUID tier, UUID... seats) {
        jdbc.sql("""
                INSERT INTO events (id, venue_id, title, starts_at, drop_at, status, per_user_limit, strategy)
                VALUES (?, ?, 'Test event', now() + interval '30 days', now() - interval '1 hour',
                        'LIVE', 4, 'PESSIMISTIC')
                """).params(event, VENUE).update();
        jdbc.sql("INSERT INTO price_tiers (id, event_id, name, price_paise) VALUES (?, ?, 'GOLD', 150000)")
                .params(tier, event).update();
        for (UUID seat : seats) {
            jdbc.sql("INSERT INTO event_seats (event_id, seat_id, tier_id, status) VALUES (?, ?, ?, 'AVAILABLE')")
                    .params(event, seat, tier).update();
        }
    }

    // --- hold ------------------------------------------------------------------------------------

    @Test
    void kindIsPessimistic() {
        assertThat(strategy.kind()).isEqualTo(StrategyKind.PESSIMISTIC);
    }

    @Test
    void holdTakesEverySeatAndCreatesAnActiveHold() {
        UUID holdId = UUID.randomUUID();

        HoldOutcome outcome = strategy.hold(request(holdId, EVENT_A, SEAT_3, SEAT_1, SEAT_2));

        assertThat(outcome).isInstanceOfSatisfying(Held.class, held -> {
            assertThat(held.holdId()).isEqualTo(holdId);
            assertThat(held.seatIds()).containsExactlyInAnyOrder(SEAT_1, SEAT_2, SEAT_3);
        });
        for (UUID seat : List.of(SEAT_1, SEAT_2, SEAT_3)) {
            assertThat(seat(EVENT_A, seat)).isEqualTo(new SeatRow(SeatStatus.HELD, holdId, null, 1));
        }
        assertThat(seat(EVENT_A, SEAT_4)).as("seat not in the request").isEqualTo(SeatRow.FRESH);

        HoldRow hold = hold(holdId).orElseThrow();
        assertThat(hold.status()).isEqualTo("ACTIVE");
        assertThat(hold.eventId()).isEqualTo(EVENT_A);
        assertThat(hold.userId()).isEqualTo(USER);
        assertThat(hold.seatIds()).containsExactlyInAnyOrder(SEAT_1, SEAT_2, SEAT_3);
        // Expiry comes from the database clock (D-015): created_at defaults to now(), and expires_at
        // must be now() + holdFor in the same transaction, so the gap is exactly the duration.
        assertThat(Duration.between(hold.createdAt(), hold.expiresAt())).isEqualTo(FIVE_MINUTES);
        assertThat(((Held) outcome).expiresAt()).isEqualTo(hold.expiresAt());
    }

    @Test
    void holdIsAllOrNothingWhenOneSeatIsTaken() {
        UUID otherHold = UUID.randomUUID();
        holdDirectly(otherHold, EVENT_A, SEAT_2);
        UUID holdId = UUID.randomUUID();

        HoldOutcome outcome = strategy.hold(request(holdId, EVENT_A, SEAT_1, SEAT_2, SEAT_3));

        assertThat(outcome).isEqualTo(new Rejected(HoldRejection.SEAT_TAKEN, List.of(SEAT_2)));
        assertThat(seat(EVENT_A, SEAT_1)).isEqualTo(SeatRow.FRESH);
        assertThat(seat(EVENT_A, SEAT_3)).isEqualTo(SeatRow.FRESH);
        assertThat(seat(EVENT_A, SEAT_2)).as("the other hold keeps its seat")
                .isEqualTo(new SeatRow(SeatStatus.HELD, otherHold, null, 1));
        assertThat(hold(holdId)).as("no hold row for a rejected request").isEmpty();
    }

    @Test
    void holdRejectsASoldSeat() {
        UUID orderId = sellDirectly(EVENT_A, SEAT_4);
        UUID holdId = UUID.randomUUID();

        HoldOutcome outcome = strategy.hold(request(holdId, EVENT_A, SEAT_4, SEAT_1));

        assertThat(outcome).isEqualTo(new Rejected(HoldRejection.SEAT_TAKEN, List.of(SEAT_4)));
        assertThat(seat(EVENT_A, SEAT_4)).isEqualTo(new SeatRow(SeatStatus.SOLD, null, orderId, 1));
        assertThat(seat(EVENT_A, SEAT_1)).isEqualTo(SeatRow.FRESH);
        assertThat(hold(holdId)).isEmpty();
    }

    @Test
    void holdShorterThanOneSecondIsRejectedBeforeAnyStrategyRuns() {
        // Below 1 ms the expiry would equal created_at and break ck_holds_expires_after_created.
        assertThatThrownBy(() -> new HoldRequest(UUID.randomUUID(), EVENT_A, USER, List.of(SEAT_1),
                Duration.ofNanos(500_000)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void holdRollsBackEverythingWhenAWriteFailsHalfway() {
        // A trigger (test-only) makes the UPDATE of SEAT_3 fail. Seats are written in Postgres
        // seat_id order (SEAT_1, SEAT_3, SEAT_2), so by then the hold row and SEAT_1 are already
        // written. @Transactional must roll all of it back.
        jdbc.sql("""
                CREATE FUNCTION test_fail_seat_update() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'test: simulated failure'; END $$
                """).update();
        jdbc.sql("CREATE TRIGGER test_fail_seat_3 BEFORE UPDATE ON event_seats FOR EACH ROW "
                + "WHEN (NEW.seat_id = '" + SEAT_3 + "') EXECUTE FUNCTION test_fail_seat_update()").update();
        try {
            UUID holdId = UUID.randomUUID();

            assertThatThrownBy(() -> strategy.hold(request(holdId, EVENT_A, SEAT_1, SEAT_2, SEAT_3)))
                    .hasStackTraceContaining("test: simulated failure");

            assertThat(hold(holdId)).as("hold row rolled back").isEmpty();
            for (UUID seat : List.of(SEAT_1, SEAT_2, SEAT_3)) {
                assertThat(seat(EVENT_A, seat)).as("seat %s rolled back", seat).isEqualTo(SeatRow.FRESH);
            }
        } finally {
            jdbc.sql("DROP TRIGGER test_fail_seat_3 ON event_seats").update();
            jdbc.sql("DROP FUNCTION test_fail_seat_update()").update();
        }
    }

    @Test
    void duplicateSeatIdIsRejectedBeforeAnyStrategyRuns() {
        // Validated in HoldRequest, so every strategy gets the check (PLAN note on M1.4).
        assertThatThrownBy(() -> request(UUID.randomUUID(), EVENT_A, SEAT_1, SEAT_2, SEAT_1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(SEAT_1.toString());
    }

    @Test
    void unknownSeatIsRejectedAndNothingChanges() {
        UUID unknown = UUID.randomUUID();
        UUID holdId = UUID.randomUUID();

        HoldOutcome outcome = strategy.hold(request(holdId, EVENT_A, SEAT_1, unknown));

        assertThat(outcome).isEqualTo(new Rejected(HoldRejection.UNKNOWN_SEAT, List.of(unknown)));
        assertThat(seat(EVENT_A, SEAT_1)).isEqualTo(SeatRow.FRESH);
        assertThat(hold(holdId)).isEmpty();
    }

    @Test
    void unknownSeatWinsOverATakenSeat() {
        // A request naming a seat that doesn't exist is malformed whatever else is in it, so
        // UNKNOWN_SEAT is reported even when another requested seat is also taken.
        holdDirectly(UUID.randomUUID(), EVENT_A, SEAT_2);
        UUID unknown = UUID.randomUUID();
        UUID holdId = UUID.randomUUID();

        HoldOutcome outcome = strategy.hold(request(holdId, EVENT_A, SEAT_2, unknown));

        assertThat(outcome).isEqualTo(new Rejected(HoldRejection.UNKNOWN_SEAT, List.of(unknown)));
        assertThat(hold(holdId)).isEmpty();
    }

    @Test
    void seatOfAnotherEventIsUnknown() {
        UUID holdId = UUID.randomUUID();

        HoldOutcome outcome = strategy.hold(request(holdId, EVENT_A, SEAT_1, SEAT_B));

        assertThat(outcome).isEqualTo(new Rejected(HoldRejection.UNKNOWN_SEAT, List.of(SEAT_B)));
        assertThat(seat(EVENT_A, SEAT_1)).isEqualTo(SeatRow.FRESH);
        assertThat(seat(EVENT_B, SEAT_B)).isEqualTo(SeatRow.FRESH);
        assertThat(hold(holdId)).isEmpty();
    }

    // --- release ---------------------------------------------------------------------------------

    @Test
    void releaseByTheOwningHoldFreesItsSeats() {
        UUID holdId = UUID.randomUUID();
        holdDirectly(holdId, EVENT_A, SEAT_1, SEAT_2);

        ReleaseOutcome outcome = strategy.release(EVENT_A, holdId, ReleaseReason.RELEASED);

        assertThat(outcome).isInstanceOfSatisfying(Released.class,
                released -> assertThat(released.seatIds()).containsExactlyInAnyOrder(SEAT_1, SEAT_2));
        assertThat(seat(EVENT_A, SEAT_1)).isEqualTo(new SeatRow(SeatStatus.AVAILABLE, null, null, 2));
        assertThat(seat(EVENT_A, SEAT_2)).isEqualTo(new SeatRow(SeatStatus.AVAILABLE, null, null, 2));
        assertThat(hold(holdId).orElseThrow().status()).isEqualTo("RELEASED");
    }

    @Test
    void expiryClosesTheHoldAsExpiredAndASecondReleaseIsNotActive() {
        UUID holdId = UUID.randomUUID();
        holdDirectlyExpired(holdId, EVENT_A, SEAT_3);

        assertThat(strategy.release(EVENT_A, holdId, ReleaseReason.EXPIRED))
                .isEqualTo(new Released(List.of(SEAT_3)));
        assertThat(hold(holdId).orElseThrow().status()).isEqualTo("EXPIRED");
        assertThat(seat(EVENT_A, SEAT_3)).isEqualTo(new SeatRow(SeatStatus.AVAILABLE, null, null, 2));

        // e.g. the buyer clicks "cancel" just after the sweeper expired the hold
        assertThat(strategy.release(EVENT_A, holdId, ReleaseReason.RELEASED)).isEqualTo(new NotActive("EXPIRED"));
        assertThat(seat(EVENT_A, SEAT_3)).as("unchanged").isEqualTo(new SeatRow(SeatStatus.AVAILABLE, null, null, 2));
    }

    @Test
    void expiryOfAHoldThatHasNotExpiredChangesNothing() {
        // The sweeper picked the hold, then the buyer extended it before the sweeper got the lock.
        // release(EXPIRED) re-checks expires_at under the lock and must leave the hold alone.
        UUID holdId = UUID.randomUUID();
        holdDirectly(holdId, EVENT_A, SEAT_1);
        Instant expiresAt = hold(holdId).orElseThrow().expiresAt();

        assertThat(strategy.release(EVENT_A, holdId, ReleaseReason.EXPIRED)).isEqualTo(new NotExpired(expiresAt));
        assertThat(hold(holdId).orElseThrow().status()).isEqualTo("ACTIVE");
        assertThat(seat(EVENT_A, SEAT_1)).isEqualTo(new SeatRow(SeatStatus.HELD, holdId, null, 1));
    }

    @Test
    void sagaFailureFreesTheSeatsAndClosesTheHoldAsReleased() {
        // ck_holds_status has no separate value for a failed checkout, so the hold ends RELEASED.
        UUID holdId = UUID.randomUUID();
        holdDirectly(holdId, EVENT_A, SEAT_4);

        assertThat(strategy.release(EVENT_A, holdId, ReleaseReason.SAGA_FAILED)).isEqualTo(new Released(List.of(SEAT_4)));
        assertThat(hold(holdId).orElseThrow().status()).isEqualTo("RELEASED");
        assertThat(seat(EVENT_A, SEAT_4)).isEqualTo(new SeatRow(SeatStatus.AVAILABLE, null, null, 2));
    }

    @Test
    void releaseOfAnInconsistentHoldThrowsAndRollsBack() {
        // Broken data on purpose: the hold lists SEAT_2 but owns only SEAT_1. Release must refuse
        // (exception) and leave everything as it was, not free half the hold.
        UUID holdId = UUID.randomUUID();
        holdDirectly(holdId, EVENT_A, SEAT_1);
        jdbc.sql("UPDATE holds SET seat_ids = ? WHERE id = ?").params(new UUID[] {SEAT_1, SEAT_2}, holdId).update();

        assertThatThrownBy(() -> strategy.release(EVENT_A, holdId, ReleaseReason.RELEASED))
                .isInstanceOf(IllegalStateException.class);

        assertThat(hold(holdId).orElseThrow().status()).isEqualTo("ACTIVE");
        assertThat(seat(EVENT_A, SEAT_1)).isEqualTo(new SeatRow(SeatStatus.HELD, holdId, null, 1));
    }

    @Test
    void releaseOfAnUnknownHoldOrAHoldOfAnotherEventIsNotFound() {
        UUID holdId = UUID.randomUUID();
        holdDirectly(holdId, EVENT_A, SEAT_1);

        assertThat(strategy.release(EVENT_A, UUID.randomUUID(), ReleaseReason.RELEASED)).isEqualTo(new NotFound());
        assertThat(strategy.release(EVENT_B, holdId, ReleaseReason.RELEASED)).isEqualTo(new NotFound());
        assertThat(seat(EVENT_A, SEAT_1)).isEqualTo(new SeatRow(SeatStatus.HELD, holdId, null, 1));
        assertThat(hold(holdId).orElseThrow().status()).isEqualTo("ACTIVE");
    }

    // --- races (small; the 1000-run harness is M1.3) -----------------------------------------

    @Test
    void twoBuyersRaceForOneSeatAndExactlyOneWins() throws Exception {
        UUID holdA = UUID.randomUUID();
        UUID holdB = UUID.randomUUID();

        List<HoldOutcome> outcomes = race(
                () -> strategy.hold(request(holdA, EVENT_A, SEAT_1)),
                () -> strategy.hold(request(holdB, EVENT_A, SEAT_1)));

        List<Held> winners = outcomes.stream().filter(Held.class::isInstance).map(Held.class::cast).toList();
        assertThat(winners).hasSize(1);
        assertThat(outcomes).contains(new Rejected(HoldRejection.SEAT_TAKEN, List.of(SEAT_1)));
        UUID winner = winners.get(0).holdId();
        assertThat(seat(EVENT_A, SEAT_1)).isEqualTo(new SeatRow(SeatStatus.HELD, winner, null, 1));
        assertThat(jdbc.sql("SELECT count(*) FROM holds").query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void holdWaitsForALockedSeatThenSeesTheCommittedState() throws Exception {
        // Deterministic version of the race: another transaction locks SEAT_1 first, hold() must
        // WAIT on that lock (proved via pg_stat_activity), and after the other transaction commits
        // the seat as HELD, hold() must see that new state (Postgres re-reads a row it waited for)
        // and reject. Without FOR UPDATE the SELECT doesn't wait and reads AVAILABLE; hold() then
        // blocks later on its own UPDATE, and the version check throws: the test fails either way.
        UUID otherHold = UUID.randomUUID();
        try (Connection other = openTransaction()) {
            exec(other, "SELECT 1 FROM event_seats WHERE event_id = ? AND seat_id = ? FOR UPDATE", EVENT_A, SEAT_1);

            Future<HoldOutcome> pending = THREADS.submit(() -> strategy.hold(request(UUID.randomUUID(), EVENT_A, SEAT_1)));
            waitUntilOneSessionIsBlockedOnALock();

            exec(other, "INSERT INTO holds (id, event_id, user_id, seat_ids, expires_at, status) "
                    + "VALUES (?, ?, ?, ?, now() + interval '5 minutes', 'ACTIVE')",
                    otherHold, EVENT_A, USER, new UUID[] {SEAT_1});
            exec(other, "UPDATE event_seats SET status = 'HELD', hold_id = ?, version = version + 1 "
                    + "WHERE event_id = ? AND seat_id = ?", otherHold, EVENT_A, SEAT_1);
            other.commit();

            assertThat(pending.get(30, TimeUnit.SECONDS)).isEqualTo(new Rejected(HoldRejection.SEAT_TAKEN, List.of(SEAT_1)));
        }
        assertThat(seat(EVENT_A, SEAT_1)).isEqualTo(new SeatRow(SeatStatus.HELD, otherHold, null, 1));
    }

    @Test
    void lockOrderIsPostgresSeatIdOrder() throws Exception {
        // D-015: seats are locked in Postgres seat_id order, whatever order the request lists them in
        // and whatever order the rows are stored in (the fixture stores SEAT_2 before SEAT_1).
        // SEAT_1 comes first in Postgres order. We lock SEAT_1 elsewhere, start hold({SEAT_2, SEAT_1})
        // and wait until it blocks. If it locks in seat_id order it is blocked on SEAT_1 and has NOT
        // taken SEAT_2 yet, so a third session can lock SEAT_2 with NOWAIT (fail instead of waiting).
        try (Connection blocker = openTransaction(); Connection probe = openTransaction()) {
            exec(blocker, "SELECT 1 FROM event_seats WHERE event_id = ? AND seat_id = ? FOR UPDATE", EVENT_A, SEAT_1);

            Future<HoldOutcome> pending = THREADS.submit(
                    () -> strategy.hold(request(UUID.randomUUID(), EVENT_A, SEAT_2, SEAT_1)));
            waitUntilOneSessionIsBlockedOnALock();

            exec(probe, "SELECT 1 FROM event_seats WHERE event_id = ? AND seat_id = ? FOR UPDATE NOWAIT",
                    EVENT_A, SEAT_2);  // throws "could not obtain lock" if hold() already locked SEAT_2
            probe.rollback();
            blocker.rollback();

            assertThat(pending.get(30, TimeUnit.SECONDS)).isInstanceOf(Held.class);
        }
    }

    @Test
    void cancelAndExpiryOfTheSameHoldTakeTurns() throws Exception {
        // A buyer's cancel and the sweeper hit the same (expired) hold at once. Both lock the hold row
        // first, so the second waits, then sees the status the first wrote: one Released, one NotActive.
        UUID holdId = UUID.randomUUID();
        holdDirectlyExpired(holdId, EVENT_A, SEAT_1, SEAT_2);

        List<ReleaseOutcome> outcomes = race(
                () -> strategy.release(EVENT_A, holdId, ReleaseReason.RELEASED),
                () -> strategy.release(EVENT_A, holdId, ReleaseReason.EXPIRED));

        assertThat(outcomes).filteredOn(Released.class::isInstance).hasSize(1);
        assertThat(outcomes).filteredOn(NotActive.class::isInstance).hasSize(1);
        assertThat(seat(EVENT_A, SEAT_1)).isEqualTo(new SeatRow(SeatStatus.AVAILABLE, null, null, 2));
        assertThat(seat(EVENT_A, SEAT_2)).isEqualTo(new SeatRow(SeatStatus.AVAILABLE, null, null, 2));
    }

    @Test
    void oppositeRequestOrdersBothComplete() throws Exception {
        // Smoke test only: {SEAT_1, SEAT_2} and {SEAT_2, SEAT_1} at once, 20 times; exactly one wins
        // each round and nothing throws. It can NOT prove the lock order (Postgres locks in the order
        // its scan returns rows, which doesn't depend on the order of ids in the IN list, so these two
        // requests lock in the same order even without ORDER BY). lockOrderIsPostgresSeatIdOrder is
        // the real test of the lock order; M1.3's harness is the load test.
        for (int round = 0; round < 20; round++) {
            insertFixture();
            UUID holdA = UUID.randomUUID();
            UUID holdB = UUID.randomUUID();

            List<HoldOutcome> outcomes = race(
                    () -> strategy.hold(request(holdA, EVENT_A, SEAT_1, SEAT_2)),
                    () -> strategy.hold(request(holdB, EVENT_A, SEAT_2, SEAT_1)));

            assertThat(outcomes).as("round %d", round).filteredOn(Held.class::isInstance).hasSize(1);
            assertThat(outcomes).as("round %d", round).filteredOn(Rejected.class::isInstance).hasSize(1);
        }
    }

    // --- helpers ---------------------------------------------------------------------------------

    static HoldRequest request(UUID holdId, UUID eventId, UUID... seats) {
        return new HoldRequest(holdId, eventId, USER, List.of(seats), FIVE_MINUTES);
    }

    /**
     * Starts both calls at the same moment and returns their results (rethrowing any exception). This
     * makes overlap likely, not certain; the deterministic lock tests above don't rely on timing.
     */
    @SafeVarargs
    private static <T> List<T> race(Callable<T>... calls) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        for (Callable<T> call : calls) {
            futures.add(THREADS.submit(() -> {
                start.await();
                return call.call();
            }));
        }
        start.countDown();
        List<T> results = new ArrayList<>();
        for (Future<T> future : futures) {
            results.add(future.get(30, TimeUnit.SECONDS));
        }
        return results;
    }

    /** A plain JDBC connection with its own transaction, outside Spring: "another buyer". */
    private Connection openTransaction() throws SQLException {
        Connection connection = dataSource.getConnection();
        connection.setAutoCommit(false);
        return connection;
    }

    private static void exec(Connection connection, String sql, Object... params) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                statement.setObject(i + 1, params[i]);
            }
            statement.execute();
        }
    }

    /** Polls Postgres until exactly one session is waiting for a row lock (at most 10 s). */
    private void waitUntilOneSessionIsBlockedOnALock() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            long waiting = jdbc.sql("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock'")
                    .query(Long.class).single();
            if (waiting == 1) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("Expected exactly one session waiting for a row lock within 10 s");
    }

    /** Like {@link #holdDirectly}, but the hold expired a minute ago (created 6 minutes ago). */
    private void holdDirectlyExpired(UUID holdId, UUID eventId, UUID... seats) {
        holdDirectly(holdId, eventId, seats);
        jdbc.sql("""
                UPDATE holds SET created_at = now() - interval '6 minutes', expires_at = now() - interval '1 minute'
                WHERE id = ?
                """).param(holdId).update();
    }

    /** Test setup without the strategy: an ACTIVE hold that owns these seats (version 1). */
    private void holdDirectly(UUID holdId, UUID eventId, UUID... seats) {
        jdbc.sql("""
                INSERT INTO holds (id, event_id, user_id, seat_ids, expires_at, status)
                VALUES (?, ?, ?, ?, now() + interval '5 minutes', 'ACTIVE')
                """).params(holdId, eventId, USER, seats).update();
        for (UUID seat : seats) {
            jdbc.sql("""
                    UPDATE event_seats SET status = 'HELD', hold_id = ?, version = version + 1
                    WHERE event_id = ? AND seat_id = ?
                    """).params(holdId, eventId, seat).update();
        }
    }

    /** Test setup without the strategy: a SOLD seat owned by a new PAID order (version 1). */
    private UUID sellDirectly(UUID eventId, UUID seat) {
        UUID orderId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO orders (id, user_id, event_id, status, total_paise, saga_state)
                VALUES (?, ?, ?, 'PAID', 150000, 'SEATS_SOLD')
                """).params(orderId, USER, eventId).update();
        jdbc.sql("""
                UPDATE event_seats SET status = 'SOLD', order_id = ?, version = version + 1
                WHERE event_id = ? AND seat_id = ?
                """).params(orderId, eventId, seat).update();
        return orderId;
    }

    record SeatRow(SeatStatus status, UUID holdId, UUID orderId, long version) {
        static final SeatRow FRESH = new SeatRow(SeatStatus.AVAILABLE, null, null, 0);
    }

    private SeatRow seat(UUID eventId, UUID seatId) {
        return jdbc.sql("SELECT status, hold_id, order_id, version FROM event_seats WHERE event_id = ? AND seat_id = ?")
                .params(eventId, seatId)
                .query((rs, n) -> new SeatRow(SeatStatus.valueOf(rs.getString("status")),
                        rs.getObject("hold_id", UUID.class), rs.getObject("order_id", UUID.class),
                        rs.getLong("version")))
                .single();
    }

    record HoldRow(UUID eventId, UUID userId, List<UUID> seatIds, String status, Instant createdAt,
            Instant expiresAt) {
    }

    private Optional<HoldRow> hold(UUID holdId) {
        return jdbc.sql("SELECT event_id, user_id, seat_ids, status, created_at, expires_at FROM holds WHERE id = ?")
                .param(holdId)
                .query((rs, n) -> new HoldRow(rs.getObject("event_id", UUID.class),
                        rs.getObject("user_id", UUID.class), uuids(rs.getArray("seat_ids")), rs.getString("status"),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("expires_at", OffsetDateTime.class).toInstant()))
                .optional();
    }

    private static List<UUID> uuids(Array array) throws SQLException {
        return Arrays.asList((UUID[]) array.getArray());
    }
}
