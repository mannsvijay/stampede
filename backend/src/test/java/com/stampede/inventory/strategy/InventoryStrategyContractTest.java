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
import com.stampede.testsupport.ExpectInvariantViolations;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import com.stampede.testsupport.TestDatabases;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The behaviour every Postgres inventory strategy must have, against a real Postgres (M1.2, D-015;
 * shared since M2.1). A contract test: one set of tests, run once per implementation by a subclass
 * ({@link PessimisticInventoryStrategyTest}, {@link OptimisticInventoryStrategyTest}) that supplies the
 * strategy and adds tests of its own mechanism.
 * <p>
 * The lock tests here hold for both strategies because they observe rows, not SQL: the pessimistic
 * strategy locks seats with {@code SELECT ... FOR UPDATE}, the optimistic one with its version-checked
 * UPDATEs, and both in Postgres {@code seat_id} order.
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
@Import(StrategyTestConfig.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
abstract class InventoryStrategyContractTest {

    /**
     * One database for every subclass, on the shared test Postgres (TestDatabases). Both subclasses
     * inherit this method, so they share one Spring context and one database, as before.
     */
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabases.register(registry, "strategy_contract");
    }

    /** Two threads for the race tests; a new pool per test, so no subclass inherits a shut-down one. */
    protected ExecutorService THREADS;

    static final UUID USER = UUID.fromString("00000000-0000-4000-8000-0000000000a1");
    static final UUID OTHER_USER = UUID.fromString("00000000-0000-4000-8000-0000000000a2");
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

    /** The strategy under test, from {@link #strategyUnderTest()}; set before each test. */
    InventoryStrategy strategy;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    DataSource dataSource;

    /** The strategy this subclass tests. */
    protected abstract InventoryStrategy strategyUnderTest();

    @BeforeEach
    void startThreadsAndPickStrategy() {
        THREADS = Executors.newFixedThreadPool(2);
        strategy = strategyUnderTest();
    }

    @AfterEach
    void stopThreads() {
        THREADS.shutdownNow();
    }

    @BeforeEach
    void insertFixture() {
        jdbc.sql("TRUNCATE users, venues CASCADE").update();
        jdbc.sql("INSERT INTO users (id, email, password_hash) VALUES (?, 'buyer@example.test', 'x')")
                .param(USER).update();
        jdbc.sql("INSERT INTO users (id, email, password_hash) VALUES (?, 'other@example.test', 'x')")
                .param(OTHER_USER).update();
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

    protected void insertEvent(UUID event, UUID tier, UUID... seats) {
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

        assertSeatTaken(outcome, SEAT_2);
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

        assertSeatTaken(outcome, SEAT_4);
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
    @ExpectInvariantViolations(rules = {"I3", "I8"},
            reason = "Deliberate drift: the hold lists a seat it doesn't own (and isn't counted) so release must refuse")
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

    // --- takenAgoMs (D-016) ----------------------------------------------------------------------

    @Test
    void takenAgoMsComesFromTheOwningHoldsCreatedAt() {
        UUID otherHold = UUID.randomUUID();
        holdDirectly(otherHold, EVENT_A, SEAT_2);
        jdbc.sql("UPDATE holds SET created_at = now() - interval '90 seconds' WHERE id = ?").param(otherHold).update();

        Rejected rejected = (Rejected) strategy.hold(request(UUID.randomUUID(), EVENT_A, SEAT_2));

        assertThat(rejected.takenAgoMs()).isBetween(90_000L, 100_000L);
    }

    @Test
    void takenAgoMsOfASoldSeatComesFromItsOrder() {
        // A SOLD seat no longer points to its hold (D-014), so the order's created_at is used.
        UUID orderId = sellDirectly(EVENT_A, SEAT_4);
        jdbc.sql("UPDATE orders SET created_at = now() - interval '2 minutes' WHERE id = ?").param(orderId).update();

        Rejected rejected = (Rejected) strategy.hold(request(UUID.randomUUID(), EVENT_A, SEAT_4));

        assertThat(rejected.takenAgoMs()).isBetween(120_000L, 130_000L);
    }

    @Test
    void takenAgoMsIsTheMostRecentlyTakenSeat() {
        UUID oldHold = UUID.randomUUID();
        holdDirectly(oldHold, EVENT_A, SEAT_1);
        jdbc.sql("UPDATE holds SET created_at = now() - interval '4 minutes' WHERE id = ?").param(oldHold).update();
        UUID newHold = UUID.randomUUID();
        holdDirectly(newHold, EVENT_A, SEAT_2);
        jdbc.sql("UPDATE holds SET created_at = now() - interval '10 seconds' WHERE id = ?").param(newHold).update();

        Rejected rejected = (Rejected) strategy.hold(request(UUID.randomUUID(), EVENT_A, SEAT_1, SEAT_2));

        assertThat(rejected.seatIds()).containsExactlyInAnyOrder(SEAT_1, SEAT_2);
        assertThat(rejected.takenAgoMs()).isBetween(10_000L, 20_000L);
    }

    @Test
    void onlySeatTakenCarriesTakenAgoMs() {
        assertThatThrownBy(() -> new Rejected(HoldRejection.SEAT_TAKEN, List.of(SEAT_1), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Rejected(HoldRejection.LIMIT_EXCEEDED, List.of(SEAT_1), 5L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- per-user cap (D-005, M1.4) ------------------------------------------------------------

    @Test
    void holdAddsItsSeatsToTheUsersAllowance() {
        assertThat(used(EVENT_A)).as("no row before the first hold").isNull();

        strategy.hold(request(UUID.randomUUID(), EVENT_A, SEAT_1, SEAT_2));
        assertThat(used(EVENT_A)).isEqualTo(2);

        strategy.hold(request(UUID.randomUUID(), EVENT_A, SEAT_3));
        assertThat(used(EVENT_A)).isEqualTo(3);
        assertThat(usedBy(OTHER_USER, EVENT_A)).as("another user's allowance").isNull();
    }

    @Test
    void holdUpToExactlyTheLimitSucceeds() {
        // per_user_limit is 4 in the fixture
        assertThat(strategy.hold(request(UUID.randomUUID(), EVENT_A, SEAT_1, SEAT_2, SEAT_3, SEAT_4)))
                .isInstanceOf(Held.class);
        assertThat(used(EVENT_A)).isEqualTo(4);
    }

    @Test
    void holdOverTheLimitIsRejectedAndWritesNothing() {
        strategy.hold(request(UUID.randomUUID(), EVENT_A, SEAT_1, SEAT_2, SEAT_3));
        jdbc.sql("UPDATE events SET per_user_limit = 3 WHERE id = ?").param(EVENT_A).update();
        UUID holdId = UUID.randomUUID();

        HoldOutcome outcome = strategy.hold(request(holdId, EVENT_A, SEAT_4));

        assertThat(outcome).isEqualTo(new Rejected(HoldRejection.LIMIT_EXCEEDED, List.of(SEAT_4)));
        assertThat(used(EVENT_A)).isEqualTo(3);
        assertThat(seat(EVENT_A, SEAT_4)).isEqualTo(SeatRow.FRESH);
        assertThat(hold(holdId)).isEmpty();
    }

    @Test
    void firstHoldLargerThanTheLimitIsRejectedWithoutCreatingAnAllowanceRow() {
        jdbc.sql("UPDATE events SET per_user_limit = 2 WHERE id = ?").param(EVENT_A).update();
        UUID holdId = UUID.randomUUID();

        HoldOutcome outcome = strategy.hold(request(holdId, EVENT_A, SEAT_1, SEAT_2, SEAT_3));

        assertThat(outcome).isEqualTo(new Rejected(HoldRejection.LIMIT_EXCEEDED, List.of(SEAT_1, SEAT_2, SEAT_3)));
        assertThat(used(EVENT_A)).isNull();
        assertThat(seat(EVENT_A, SEAT_1)).isEqualTo(SeatRow.FRESH);
        assertThat(hold(holdId)).isEmpty();
    }

    @Test
    void theCapIsPerUserAndPerEvent() {
        strategy.hold(request(UUID.randomUUID(), EVENT_A, SEAT_1, SEAT_2, SEAT_3, SEAT_4));

        assertThat(strategy.hold(request(UUID.randomUUID(), EVENT_B, SEAT_B))).as("same user, other event")
                .isInstanceOf(Held.class);
        assertThat(used(EVENT_A)).isEqualTo(4);
        assertThat(used(EVENT_B)).isEqualTo(1);
    }

    @Test
    void seatTakenWinsOverLimitExceeded() {
        // The cap is only checked once every seat could be held (step 4b comes after step 4).
        UUID holdId = UUID.randomUUID();
        holdDirectly(holdId, EVENT_A, SEAT_1, SEAT_2, SEAT_3);
        jdbc.sql("UPDATE events SET per_user_limit = 3 WHERE id = ?").param(EVENT_A).update();  // USER is at it

        assertSeatTaken(strategy.hold(request(UUID.randomUUID(), EVENT_A, SEAT_2)), SEAT_2);
        assertThat(strategy.hold(request(UUID.randomUUID(), EVENT_A, SEAT_4)))
                .isEqualTo(new Rejected(HoldRejection.LIMIT_EXCEEDED, List.of(SEAT_4)));
    }

    @Test
    void releaseExpiryAndSagaFailureGiveTheSeatsBack() {
        UUID released = UUID.randomUUID();
        holdDirectly(released, EVENT_A, SEAT_1);
        UUID expired = UUID.randomUUID();
        holdDirectlyExpired(expired, EVENT_A, SEAT_2, SEAT_3);
        UUID failed = UUID.randomUUID();
        holdDirectly(failed, EVENT_A, SEAT_4);
        assertThat(used(EVENT_A)).isEqualTo(4);

        strategy.release(EVENT_A, released, ReleaseReason.RELEASED);
        assertThat(used(EVENT_A)).isEqualTo(3);
        strategy.release(EVENT_A, expired, ReleaseReason.EXPIRED);
        assertThat(used(EVENT_A)).isEqualTo(1);
        strategy.release(EVENT_A, failed, ReleaseReason.SAGA_FAILED);
        assertThat(used(EVENT_A)).isEqualTo(0);

        // and the freed allowance can be used again
        assertThat(strategy.hold(request(UUID.randomUUID(), EVENT_A, SEAT_1, SEAT_2, SEAT_3, SEAT_4)))
                .isInstanceOf(Held.class);
    }

    @Test
    void releaseThatChangesNothingGivesNothingBack() {
        UUID holdId = UUID.randomUUID();
        holdDirectly(holdId, EVENT_A, SEAT_1, SEAT_2);
        strategy.release(EVENT_A, holdId, ReleaseReason.RELEASED);

        assertThat(strategy.release(EVENT_A, holdId, ReleaseReason.RELEASED)).isEqualTo(new NotActive("RELEASED"));
        assertThat(used(EVENT_A)).as("only the first release decrements").isEqualTo(0);

        UUID notExpired = UUID.randomUUID();
        holdDirectly(notExpired, EVENT_A, SEAT_3);
        assertThat(strategy.release(EVENT_A, notExpired, ReleaseReason.EXPIRED)).isInstanceOf(NotExpired.class);
        assertThat(used(EVENT_A)).isEqualTo(1);
    }

    @Test
    @ExpectInvariantViolations(rules = {"I8"},
            reason = "Deliberate drift: used is set below the hold's seats so release must throw and roll back")
    void releaseWithADriftedAllowanceThrowsAndRollsBack() {
        // used is lower than the hold's seats: decrementing would break ck_..._used_nonnegative.
        UUID holdId = UUID.randomUUID();
        holdDirectly(holdId, EVENT_A, SEAT_1, SEAT_2);
        jdbc.sql("UPDATE user_event_allowance SET used = 1 WHERE event_id = ? AND user_id = ?")
                .params(EVENT_A, USER).update();

        assertThatThrownBy(() -> strategy.release(EVENT_A, holdId, ReleaseReason.RELEASED))
                .hasStackTraceContaining("ck_user_event_allowance_used_nonnegative");

        assertThat(hold(holdId).orElseThrow().status()).isEqualTo("ACTIVE");
        assertThat(seat(EVENT_A, SEAT_1)).isEqualTo(new SeatRow(SeatStatus.HELD, holdId, null, 1));
        assertThat(used(EVENT_A)).isEqualTo(1);
    }

    @Test
    @ExpectInvariantViolations(rules = {"I8"},
            reason = "Deliberate drift: the allowance row is deleted so release must throw and roll back")
    void releaseWithoutAnAllowanceRowThrowsAndRollsBack() {
        UUID holdId = UUID.randomUUID();
        holdDirectly(holdId, EVENT_A, SEAT_1);
        jdbc.sql("DELETE FROM user_event_allowance WHERE event_id = ? AND user_id = ?").params(EVENT_A, USER).update();

        assertThatThrownBy(() -> strategy.release(EVENT_A, holdId, ReleaseReason.RELEASED))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No allowance row");

        assertThat(hold(holdId).orElseThrow().status()).isEqualTo("ACTIVE");
        assertThat(seat(EVENT_A, SEAT_1)).isEqualTo(new SeatRow(SeatStatus.HELD, holdId, null, 1));
    }

    @Test
    void holdLocksTheAllowanceRowAfterTheSeats() throws Exception {
        // D-015 lock order: seats, then allowance. Another session locks USER's allowance row; hold()
        // must block on it while ALREADY owning both seat locks. If it locked the allowance first, it
        // would block before taking any seat, and the NOWAIT probes below would succeed.
        holdDirectly(UUID.randomUUID(), EVENT_A, SEAT_4);  // gives USER an allowance row to lock
        try (Connection blocker = openTransaction(); Connection probe = openTransaction()) {
            exec(blocker, "SELECT 1 FROM user_event_allowance WHERE event_id = ? AND user_id = ? FOR UPDATE",
                    EVENT_A, USER);

            Future<HoldOutcome> pending = THREADS.submit(
                    () -> strategy.hold(request(UUID.randomUUID(), EVENT_A, SEAT_1, SEAT_2)));
            waitUntilOneSessionIsBlockedOnALock();

            for (UUID seat : List.of(SEAT_1, SEAT_2)) {
                assertThatThrownBy(() -> exec(probe,
                        "SELECT 1 FROM event_seats WHERE event_id = ? AND seat_id = ? FOR UPDATE NOWAIT", EVENT_A, seat))
                        .as("seat %s is already locked by hold()", seat)
                        .hasMessageContaining("could not obtain lock");
                probe.rollback();
            }
            blocker.rollback();

            assertThat(pending.get(30, TimeUnit.SECONDS)).isInstanceOf(Held.class);
        }
        assertThat(used(EVENT_A)).isEqualTo(3);
    }

    @Test
    void releaseLocksTheAllowanceRowAfterTheHoldAndItsSeats() throws Exception {
        // Same proof for release: hold row, seats, then allowance.
        UUID holdId = UUID.randomUUID();
        holdDirectly(holdId, EVENT_A, SEAT_1, SEAT_2);
        try (Connection blocker = openTransaction(); Connection probe = openTransaction()) {
            exec(blocker, "SELECT 1 FROM user_event_allowance WHERE event_id = ? AND user_id = ? FOR UPDATE",
                    EVENT_A, USER);

            Future<ReleaseOutcome> pending = THREADS.submit(
                    () -> strategy.release(EVENT_A, holdId, ReleaseReason.RELEASED));
            waitUntilOneSessionIsBlockedOnALock();

            assertThatThrownBy(() -> exec(probe, "SELECT 1 FROM holds WHERE id = ? FOR UPDATE NOWAIT", holdId))
                    .as("the hold row is already locked by release()")
                    .hasMessageContaining("could not obtain lock");
            probe.rollback();
            for (UUID seat : List.of(SEAT_1, SEAT_2)) {
                assertThatThrownBy(() -> exec(probe,
                        "SELECT 1 FROM event_seats WHERE event_id = ? AND seat_id = ? FOR UPDATE NOWAIT", EVENT_A, seat))
                        .as("seat %s is already locked by release()", seat)
                        .hasMessageContaining("could not obtain lock");
                probe.rollback();
            }
            blocker.rollback();

            assertThat(pending.get(30, TimeUnit.SECONDS)).isInstanceOf(Released.class);
        }
        assertThat(used(EVENT_A)).isEqualTo(0);
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
        assertThat(outcomes).filteredOn(Rejected.class::isInstance).singleElement()
                .satisfies(rejected -> assertSeatTaken((HoldOutcome) rejected, SEAT_1));
        UUID winner = winners.get(0).holdId();
        assertThat(seat(EVENT_A, SEAT_1)).isEqualTo(new SeatRow(SeatStatus.HELD, winner, null, 1));
        assertThat(jdbc.sql("SELECT count(*) FROM holds").query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void holdWaitsForALockedSeatThenSeesTheCommittedState() throws Exception {
        // Deterministic version of the race: another transaction locks SEAT_1 first, hold() must
        // WAIT on that lock (proved via pg_stat_activity), and after the other transaction commits
        // the seat as HELD, hold() must reject with SEAT_TAKEN, never throw and never double-hold.
        // Pessimistic waits in its SELECT ... FOR UPDATE and then reads the committed row. Optimistic
        // reads AVAILABLE without waiting, waits in its UPDATE, loses the version check, rolls back
        // and retries; the retry reads HELD (OptimisticInventoryStrategyTest counts the tries).
        UUID otherHold = UUID.randomUUID();
        try (Connection other = openTransaction()) {
            exec(other, "SELECT 1 FROM event_seats WHERE event_id = ? AND seat_id = ? FOR UPDATE", EVENT_A, SEAT_1);

            Future<HoldOutcome> pending = THREADS.submit(() -> strategy.hold(request(UUID.randomUUID(), EVENT_A, SEAT_1)));
            waitUntilOneSessionIsBlockedOnALock();

            exec(other, "INSERT INTO holds (id, event_id, user_id, seat_ids, expires_at, status) "
                    + "VALUES (?, ?, ?, ?, now() + interval '5 minutes', 'ACTIVE')",
                    otherHold, EVENT_A, USER, new UUID[] {SEAT_1});
            // The allowance row the strategy would write in the same transaction (D-016).
            exec(other, "INSERT INTO user_event_allowance (event_id, user_id, used) VALUES (?, ?, 1)", EVENT_A, USER);
            exec(other, "UPDATE event_seats SET status = 'HELD', hold_id = ?, version = version + 1 "
                    + "WHERE event_id = ? AND seat_id = ?", otherHold, EVENT_A, SEAT_1);
            other.commit();

            assertSeatTaken(pending.get(30, TimeUnit.SECONDS), SEAT_1);
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
        // Pessimistic would hold SEAT_2 through its FOR UPDATE, optimistic through its UPDATE of
        // SEAT_2 (an UPDATE locks its row until commit): the probe catches either.
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

    // --- sell (M1.5, D-017) ----------------------------------------------------------------------

    @Test
    @ExpectInvariantViolations(rules = {"O1", "O5", "O7"},
            reason = "Strategy unit test of step 3 only (D-017): the order is a bare PENDING row and step 4 (booking marks it PAID) never runs, so its seats are SOLD to a PENDING order")
    void sellMovesEverySeatToSoldAndConvertsTheHold() {
        UUID holdId = UUID.randomUUID();
        holdDirectly(holdId, EVENT_A, SEAT_1, SEAT_2);
        UUID orderId = pendingOrder(holdId, EVENT_A);

        SellOutcome outcome = strategy.sell(EVENT_A, holdId, orderId);

        assertThat(outcome).isInstanceOfSatisfying(SellOutcome.Sold.class,
                sold -> assertThat(sold.seatIds()).containsExactlyInAnyOrder(SEAT_1, SEAT_2));
        // The order is the owner now; the hold pointer is cleared (D-014); version bumped again.
        assertThat(seat(EVENT_A, SEAT_1)).isEqualTo(new SeatRow(SeatStatus.SOLD, null, orderId, 2));
        assertThat(seat(EVENT_A, SEAT_2)).isEqualTo(new SeatRow(SeatStatus.SOLD, null, orderId, 2));
        assertThat(hold(holdId).orElseThrow().status()).isEqualTo("CONVERTED");
        assertThat(used(EVENT_A)).as("bought seats keep counting (D-016)").isEqualTo(2);
    }

    @Test
    @ExpectInvariantViolations(rules = {"O7"},
            reason = "The refused sale leaves the bare PENDING order this strategy unit test inserted; step 4 is booking's and never runs")
    void sellOfAnActiveHoldPastItsExpiryChangesNothing() {
        // The sweeper hasn't reached it yet: status ACTIVE, expires_at in the past. Must not be sold.
        UUID holdId = UUID.randomUUID();
        holdDirectlyExpired(holdId, EVENT_A, SEAT_1);
        UUID orderId = pendingOrder(holdId, EVENT_A);

        assertThat(strategy.sell(EVENT_A, holdId, orderId)).isInstanceOf(SellOutcome.HoldExpired.class);

        assertThat(seat(EVENT_A, SEAT_1)).isEqualTo(new SeatRow(SeatStatus.HELD, holdId, null, 1));
        assertThat(hold(holdId).orElseThrow().status()).isEqualTo("ACTIVE");
        assertThat(used(EVENT_A)).isEqualTo(1);
    }

    @Test
    @ExpectInvariantViolations(rules = {"O1", "O5", "O7"},
            reason = "Strategy unit test of step 3 only (D-017): the order is a bare PENDING row and step 4 (booking marks it PAID) never runs, so its seats are SOLD to a PENDING order")
    void sellOfAReleasedOrAlreadyConvertedHoldIsNotActive() {
        UUID released = UUID.randomUUID();
        holdDirectly(released, EVENT_A, SEAT_1);
        UUID releasedOrder = pendingOrder(released, EVENT_A);
        strategy.release(EVENT_A, released, ReleaseReason.RELEASED);

        assertThat(strategy.sell(EVENT_A, released, releasedOrder)).isEqualTo(new SellOutcome.NotActive("RELEASED"));
        assertThat(seat(EVENT_A, SEAT_1)).isEqualTo(new SeatRow(SeatStatus.AVAILABLE, null, null, 2));

        UUID sold = UUID.randomUUID();
        holdDirectly(sold, EVENT_A, SEAT_2);
        UUID soldOrder = pendingOrder(sold, EVENT_A);
        strategy.sell(EVENT_A, sold, soldOrder);

        assertThat(strategy.sell(EVENT_A, sold, soldOrder)).isEqualTo(new SellOutcome.NotActive("CONVERTED"));
        assertThat(seat(EVENT_A, SEAT_2)).as("unchanged by the second sell")
                .isEqualTo(new SeatRow(SeatStatus.SOLD, null, soldOrder, 2));
    }

    @Test
    @ExpectInvariantViolations(rules = {"O7"},
            reason = "The refused sale leaves the bare PENDING order this strategy unit test inserted; step 4 is booking's and never runs")
    void sellOfAnUnknownHoldOrAHoldOfAnotherEventIsNotFound() {
        UUID holdId = UUID.randomUUID();
        holdDirectly(holdId, EVENT_A, SEAT_1);
        UUID orderId = pendingOrder(holdId, EVENT_A);

        assertThat(strategy.sell(EVENT_A, UUID.randomUUID(), orderId)).isInstanceOf(SellOutcome.NotFound.class);
        assertThat(strategy.sell(EVENT_B, holdId, orderId)).isInstanceOf(SellOutcome.NotFound.class);
        assertThat(seat(EVENT_A, SEAT_1)).isEqualTo(new SeatRow(SeatStatus.HELD, holdId, null, 1));
    }

    @Test
    @ExpectInvariantViolations(rules = {"I3", "I8", "O7"},
            reason = "Deliberate drift: the hold lists a seat it doesn't own (and isn't counted) so sell must refuse; O7 is the bare PENDING order of this unit test")
    void sellOfAnInconsistentHoldThrowsAndRollsBack() {
        // The hold lists SEAT_1 and SEAT_2 but only owns SEAT_1: never sell half a hold.
        UUID holdId = UUID.randomUUID();
        holdDirectly(holdId, EVENT_A, SEAT_1);
        jdbc.sql("UPDATE holds SET seat_ids = ? WHERE id = ?").params(new UUID[] {SEAT_1, SEAT_2}, holdId).update();
        UUID orderId = pendingOrder(holdId, EVENT_A);

        assertThatThrownBy(() -> strategy.sell(EVENT_A, holdId, orderId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("lists seats");

        assertThat(seat(EVENT_A, SEAT_1)).isEqualTo(new SeatRow(SeatStatus.HELD, holdId, null, 1));
        assertThat(hold(holdId).orElseThrow().status()).isEqualTo("ACTIVE");
    }

    @Test
    @ExpectInvariantViolations(rules = {"O1", "O5", "O7"},
            reason = "Strategy unit test of step 3 only (D-017): the order is a bare PENDING row and step 4 (booking marks it PAID) never runs, so its seats are SOLD to a PENDING order")
    void sellLocksTheHoldRowBeforeItsSeats() throws Exception {
        // D-015 lock order for paths that start from a hold: the hold row, then the seats. Another
        // session locks the hold row; sell() must block there WITHOUT owning any seat lock yet.
        UUID holdId = UUID.randomUUID();
        holdDirectly(holdId, EVENT_A, SEAT_1, SEAT_2);
        UUID orderId = pendingOrder(holdId, EVENT_A);
        try (Connection blocker = openTransaction(); Connection probe = openTransaction()) {
            exec(blocker, "SELECT 1 FROM holds WHERE id = ? FOR UPDATE", holdId);

            Future<SellOutcome> pending = THREADS.submit(() -> strategy.sell(EVENT_A, holdId, orderId));
            waitUntilOneSessionIsBlockedOnALock();

            for (UUID seat : List.of(SEAT_1, SEAT_2)) {
                exec(probe, "SELECT 1 FROM event_seats WHERE event_id = ? AND seat_id = ? FOR UPDATE NOWAIT",
                        EVENT_A, seat);  // throws "could not obtain lock" if sell() already locked a seat
            }
            probe.rollback();
            blocker.rollback();

            assertThat(pending.get(30, TimeUnit.SECONDS)).isInstanceOf(SellOutcome.Sold.class);
        }
    }

    @Test
    @ExpectInvariantViolations(rules = {"O1", "O5", "O7"},
            reason = "Strategy unit test of step 3 only (D-017): the order is a bare PENDING row and step 4 (booking marks it PAID) never runs, so its seats are SOLD to a PENDING order")
    void sellAndCancelOfTheSameHoldTakeTurns() throws Exception {
        // Both lock the hold row first, so whichever comes second sees the first one's status. Each
        // round ends either sold (cancel says CONVERTED) or released (sell says RELEASED), never both.
        for (int round = 0; round < 20; round++) {
            insertFixture();
            UUID holdId = UUID.randomUUID();
            holdDirectly(holdId, EVENT_A, SEAT_1, SEAT_2);
            UUID orderId = pendingOrder(holdId, EVENT_A);

            List<Object> outcomes = race(
                    () -> strategy.sell(EVENT_A, holdId, orderId),
                    () -> strategy.release(EVENT_A, holdId, ReleaseReason.RELEASED));

            Object sell = outcomes.get(0);
            Object release = outcomes.get(1);
            if (sell instanceof SellOutcome.Sold) {
                assertThat(release).as("round %d", round).isEqualTo(new NotActive("CONVERTED"));
                assertThat(seat(EVENT_A, SEAT_1).status()).isEqualTo(SeatStatus.SOLD);
                assertThat(seat(EVENT_A, SEAT_2).status()).isEqualTo(SeatStatus.SOLD);
                assertThat(used(EVENT_A)).isEqualTo(2);
            } else {
                assertThat(sell).as("round %d", round).isEqualTo(new SellOutcome.NotActive("RELEASED"));
                assertThat(release).isInstanceOf(Released.class);
                assertThat(seat(EVENT_A, SEAT_1)).isEqualTo(new SeatRow(SeatStatus.AVAILABLE, null, null, 2));
                assertThat(seat(EVENT_A, SEAT_2)).isEqualTo(new SeatRow(SeatStatus.AVAILABLE, null, null, 2));
                assertThat(used(EVENT_A)).isEqualTo(0);
            }
        }
    }

    // --- helpers ---------------------------------------------------------------------------------

    /** A PENDING order of USER for this hold, as booking's step 1 commits it before the sale. */
    protected UUID pendingOrder(UUID holdId, UUID eventId) {
        UUID orderId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO orders (id, user_id, event_id, hold_id, status, total_paise, saga_state)
                VALUES (?, ?, ?, ?, 'PENDING', 150000, 'ORDER_CREATED')
                """).params(orderId, USER, eventId, holdId).update();
        return orderId;
    }

    static HoldRequest request(UUID holdId, UUID eventId, UUID... seats) {
        return requestBy(USER, holdId, eventId, seats);
    }

    static HoldRequest requestBy(UUID user, UUID holdId, UUID eventId, UUID... seats) {
        return new HoldRequest(holdId, eventId, user, List.of(seats), FIVE_MINUTES);
    }

    /** SEAT_TAKEN for exactly these seats, with a takenAgoMs (D-016) that is set and not negative. */
    protected static void assertSeatTaken(HoldOutcome outcome, UUID... seats) {
        assertThat(outcome).isInstanceOfSatisfying(Rejected.class, rejected -> {
            assertThat(rejected.reason()).isEqualTo(HoldRejection.SEAT_TAKEN);
            assertThat(rejected.seatIds()).containsExactly(seats);
            assertThat(rejected.takenAgoMs()).isNotNull().isNotNegative();
        });
    }

    /** USER's allowance in this event, or null when USER has no row yet. */
    protected Integer used(UUID eventId) {
        return usedBy(USER, eventId);
    }

    protected Integer usedBy(UUID user, UUID eventId) {
        return jdbc.sql("SELECT used FROM user_event_allowance WHERE event_id = ? AND user_id = ?")
                .params(eventId, user).query(Integer.class).optional().orElse(null);
    }

    /**
     * Starts both calls at the same moment and returns their results (rethrowing any exception). This
     * makes overlap likely, not certain; the deterministic lock tests above don't rely on timing.
     */
    @SafeVarargs
    protected final <T> List<T> race(Callable<T>... calls) throws Exception {
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
    protected Connection openTransaction() throws SQLException {
        Connection connection = dataSource.getConnection();
        connection.setAutoCommit(false);
        return connection;
    }

    protected static void exec(Connection connection, String sql, Object... params) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                statement.setObject(i + 1, params[i]);
            }
            statement.execute();
        }
    }

    /** Polls Postgres until exactly one session is waiting for a row lock (at most 10 s). */
    protected void waitUntilOneSessionIsBlockedOnALock() throws InterruptedException {
        waitUntilSessionsAreBlockedOnALock(1);
    }

    /**
     * Polls Postgres until exactly {@code sessions} sessions are waiting for a lock (at most 10 s).
     * Row locks and advisory locks both show as wait_event_type 'Lock'.
     */
    protected void waitUntilSessionsAreBlockedOnALock(int sessions) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            long waiting = jdbc.sql("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock'")
                    .query(Long.class).single();
            if (waiting == sessions) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("Expected exactly " + sessions + " session(s) waiting for a lock within 10 s");
    }

    /** Like {@link #holdDirectly}, but the hold expired a minute ago (created 6 minutes ago). */
    protected void holdDirectlyExpired(UUID holdId, UUID eventId, UUID... seats) {
        holdDirectly(holdId, eventId, seats);
        jdbc.sql("""
                UPDATE holds SET created_at = now() - interval '6 minutes', expires_at = now() - interval '1 minute'
                WHERE id = ?
                """).param(holdId).update();
    }

    /**
     * Test setup without the strategy: an ACTIVE hold of USER that owns these seats (version 1), with
     * its seats counted in USER's allowance, as the strategy would leave it (D-005).
     */
    protected void holdDirectly(UUID holdId, UUID eventId, UUID... seats) {
        jdbc.sql("""
                INSERT INTO holds (id, event_id, user_id, seat_ids, expires_at, status)
                VALUES (?, ?, ?, ?, now() + interval '5 minutes', 'ACTIVE')
                """).params(holdId, eventId, USER, seats).update();
        jdbc.sql("""
                INSERT INTO user_event_allowance (event_id, user_id, used) VALUES (?, ?, ?)
                ON CONFLICT (event_id, user_id) DO UPDATE SET used = user_event_allowance.used + EXCLUDED.used
                """).params(eventId, USER, seats.length).update();
        for (UUID seat : seats) {
            jdbc.sql("""
                    UPDATE event_seats SET status = 'HELD', hold_id = ?, version = version + 1
                    WHERE event_id = ? AND seat_id = ?
                    """).params(holdId, eventId, seat).update();
        }
    }

    /**
     * Test setup without the strategy: a SOLD seat owned by a new PAID order (version 1). Since V3
     * every order has a hold, so this adds the CONVERTED hold the order came from. The rest is what a
     * finished checkout leaves (M1.7: the after-test invariant check sees it): the order's active item,
     * its SUCCEEDED payment, its ticket (saga TICKETS_ISSUED) and the seat counted in USER's allowance.
     */
    protected UUID sellDirectly(UUID eventId, UUID seat) {
        UUID holdId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO holds (id, event_id, user_id, seat_ids, expires_at, status)
                VALUES (?, ?, ?, ?, now() + interval '5 minutes', 'CONVERTED')
                """).params(holdId, eventId, USER, new UUID[] {seat}).update();
        UUID orderId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO orders (id, user_id, event_id, hold_id, status, total_paise, saga_state)
                VALUES (?, ?, ?, ?, 'PAID', 150000, 'TICKETS_ISSUED')
                """).params(orderId, USER, eventId, holdId).update();
        jdbc.sql("""
                UPDATE event_seats SET status = 'SOLD', order_id = ?, version = version + 1
                WHERE event_id = ? AND seat_id = ?
                """).params(orderId, eventId, seat).update();
        jdbc.sql("INSERT INTO order_items (order_id, event_id, seat_id, price_paise) VALUES (?, ?, ?, 150000)")
                .params(orderId, eventId, seat).update();
        jdbc.sql("INSERT INTO payments (order_id, status, amount_paise) VALUES (?, 'SUCCEEDED', 150000)")
                .param(orderId).update();
        jdbc.sql("""
                INSERT INTO tickets (order_id, event_id, seat_id, holder_user_id, signature, status, key_id,
                                     section_code, row_label, seat_number)
                VALUES (?, ?, ?, ?, decode(repeat('00', 64), 'hex'), 'ISSUED', '0123abcd', 'A', 'A', 1)
                """).params(orderId, eventId, seat, USER).update();
        jdbc.sql("""
                INSERT INTO user_event_allowance (event_id, user_id, used) VALUES (?, ?, 1)
                ON CONFLICT (event_id, user_id) DO UPDATE SET used = user_event_allowance.used + 1
                """).params(eventId, USER).update();
        return orderId;
    }

    record SeatRow(SeatStatus status, UUID holdId, UUID orderId, long version) {
        static final SeatRow FRESH = new SeatRow(SeatStatus.AVAILABLE, null, null, 0);
    }

    protected SeatRow seat(UUID eventId, UUID seatId) {
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

    protected Optional<HoldRow> hold(UUID holdId) {
        return jdbc.sql("SELECT event_id, user_id, seat_ids, status, created_at, expires_at FROM holds WHERE id = ?")
                .param(holdId)
                .query((rs, n) -> new HoldRow(rs.getObject("event_id", UUID.class),
                        rs.getObject("user_id", UUID.class), uuids(rs.getArray("seat_ids")), rs.getString("status"),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("expires_at", OffsetDateTime.class).toInstant()))
                .optional();
    }

    protected static List<UUID> uuids(Array array) throws SQLException {
        return Arrays.asList((UUID[]) array.getArray());
    }
}
