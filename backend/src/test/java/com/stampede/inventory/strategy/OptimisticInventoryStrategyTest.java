package com.stampede.inventory.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stampede.inventory.seat.SeatStatus;
import com.stampede.inventory.strategy.HoldOutcome.Held;
import com.stampede.inventory.strategy.HoldOutcome.Rejected;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import java.sql.Connection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The optimistic strategy (M2.1, D-021): the shared contract ({@link InventoryStrategyContractTest}),
 * plus its own mechanism: unlocked reads, version conflicts that roll back and retry, the retry limit,
 * the write order that keeps opposite requests from deadlocking, and the counters.
 * <p>
 * Each conflict test makes the race happen in a fixed order with a second connection (and, for the
 * deadlock test, a test-only trigger), never by timing. Seat order in Postgres for this fixture:
 * SEAT_1 (10...) < SEAT_3 (30...) < SEAT_4 (40...) < SEAT_2 (90...); Java's UUID.compareTo puts SEAT_2
 * first (D-015).
 */
class OptimisticInventoryStrategyTest extends InventoryStrategyContractTest {

    @Autowired
    OptimisticInventoryStrategy optimistic;

    @Autowired
    MeterRegistry meters;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Override
    protected InventoryStrategy strategyUnderTest() {
        return optimistic;
    }

    @Test
    void kindIsOptimistic() {
        assertThat(strategy.kind()).isEqualTo(StrategyKind.OPTIMISTIC);
    }

    // --- version conflicts and retries --------------------------------------------------------------

    @Test
    void versionConflictIsDetectedRolledBackAndRetriedThenTheTakenSeatIsRejected() throws Exception {
        // Another buyer's transaction holds SEAT_1 but hasn't committed. Our hold reads SEAT_1 as
        // AVAILABLE v0 (uncommitted changes are invisible), inserts its hold row and blocks on its
        // UPDATE of SEAT_1. The other buyer commits: Postgres re-checks our WHERE version = 0 against
        // v1, zero rows match, the try rolls back, and the retry reads SEAT_1 as HELD: SEAT_TAKEN.
        long attempts = count(HoldMetrics.ATTEMPTS);
        long conflicts = count(HoldMetrics.CONFLICTS);
        UUID otherHold = UUID.randomUUID();
        UUID holdId = UUID.randomUUID();
        try (Connection other = openTransaction()) {
            holdInOpenTransaction(other, otherHold, OTHER_USER, SEAT_1);

            Future<HoldOutcome> pending = THREADS.submit(() -> strategy.hold(request(holdId, EVENT_A, SEAT_1)));
            waitUntilOneSessionIsBlockedOnALock();
            other.commit();

            assertSeatTaken(pending.get(30, TimeUnit.SECONDS), SEAT_1);
        }
        assertThat(count(HoldMetrics.ATTEMPTS) - attempts).as("tries").isEqualTo(2);
        assertThat(count(HoldMetrics.CONFLICTS) - conflicts).as("lost version checks").isEqualTo(1);
        assertThat(seat(EVENT_A, SEAT_1)).isEqualTo(new SeatRow(SeatStatus.HELD, otherHold, null, 1));
        assertThat(hold(holdId)).as("the first try's hold row was rolled back").isEmpty();
        assertThat(used(EVENT_A)).as("USER's allowance untouched").isNull();
    }

    @Test
    void versionConflictIsRetriedAndTheRetrySucceedsWhenTheSeatIsFreeAgain() throws Exception {
        // The other transaction changes SEAT_1's version but leaves it AVAILABLE (as a hold and a
        // release in quick succession would: v0 -> v2). The first try loses; the retry reads v2 and wins.
        long attempts = count(HoldMetrics.ATTEMPTS);
        UUID holdId = UUID.randomUUID();
        try (Connection other = openTransaction()) {
            exec(other, "UPDATE event_seats SET version = version + 2 WHERE event_id = ? AND seat_id = ?",
                    EVENT_A, SEAT_1);

            Future<HoldOutcome> pending = THREADS.submit(() -> strategy.hold(request(holdId, EVENT_A, SEAT_1)));
            waitUntilOneSessionIsBlockedOnALock();
            other.commit();

            assertThat(pending.get(30, TimeUnit.SECONDS)).isInstanceOf(Held.class);
        }
        assertThat(count(HoldMetrics.ATTEMPTS) - attempts).as("tries").isEqualTo(2);
        assertThat(seat(EVENT_A, SEAT_1)).isEqualTo(new SeatRow(SeatStatus.HELD, holdId, null, 3));
        assertThat(used(EVENT_A)).isEqualTo(1);
    }

    @Test
    void aSeatThatIsAlreadyTakenIsRejectedWithoutARetry() {
        // HELD in committed data is a real answer, not a race (D-021): one try, no conflict.
        holdDirectly(UUID.randomUUID(), EVENT_A, SEAT_2);
        long attempts = count(HoldMetrics.ATTEMPTS);
        long conflicts = count(HoldMetrics.CONFLICTS);

        assertSeatTaken(strategy.hold(request(UUID.randomUUID(), EVENT_A, SEAT_1, SEAT_2)), SEAT_2);

        assertThat(count(HoldMetrics.ATTEMPTS) - attempts).isEqualTo(1);
        assertThat(count(HoldMetrics.CONFLICTS) - conflicts).isZero();
    }

    @Test
    void whenEveryTryLosesTheAnswerIsContendedAndNothingIsWritten() {
        // A test-only trigger makes every UPDATE of SEAT_2 affect zero rows (RETURN NULL in a BEFORE
        // trigger skips the row), exactly what a lost version check looks like. SEAT_1 comes first
        // in seat_id order, so each try has already written SEAT_1 and the hold row when it loses:
        // rolling back must undo both, every time.
        jdbc.sql("""
                CREATE FUNCTION test_skip_seat_update() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RETURN NULL; END $$
                """).update();
        jdbc.sql("CREATE TRIGGER test_skip_seat_2 BEFORE UPDATE ON event_seats FOR EACH ROW "
                + "WHEN (NEW.seat_id = '" + SEAT_2 + "') EXECUTE FUNCTION test_skip_seat_update()").update();
        try {
            long attempts = count(HoldMetrics.ATTEMPTS);
            long conflicts = count(HoldMetrics.CONFLICTS);
            long contended = outcomes("contended");
            UUID holdId = UUID.randomUUID();

            HoldOutcome outcome = strategy.hold(request(holdId, EVENT_A, SEAT_2, SEAT_1));

            assertThat(outcome).isEqualTo(new Rejected(HoldRejection.CONTENDED, List.of(SEAT_2)));
            assertThat(count(HoldMetrics.ATTEMPTS) - attempts).as("max-attempts (3)").isEqualTo(3);
            assertThat(count(HoldMetrics.CONFLICTS) - conflicts).isEqualTo(3);
            assertThat(outcomes("contended") - contended).isEqualTo(1);
            assertThat(seat(EVENT_A, SEAT_1)).as("written by every try, rolled back every time")
                    .isEqualTo(SeatRow.FRESH);
            assertThat(seat(EVENT_A, SEAT_2)).isEqualTo(SeatRow.FRESH);
            assertThat(hold(holdId)).isEmpty();
            assertThat(used(EVENT_A)).isNull();
        } finally {
            jdbc.sql("DROP TRIGGER test_skip_seat_2 ON event_seats").update();
            jdbc.sql("DROP FUNCTION test_skip_seat_update()").update();
        }
    }

    // --- locks ---------------------------------------------------------------------------------------

    @Test
    void oppositeOrderHoldsThatInterleaveDoNotDeadlock() throws Exception {
        // The deadlock D-015's order exists to prevent, forced deterministically:
        //   A = hold({SEAT_1, SEAT_2}) writes SEAT_1 (row lock taken) and is paused by a test trigger
        //       that waits on an advisory lock the test holds;
        //   B = hold({SEAT_2, SEAT_1}) starts while A holds SEAT_1.
        // In seat_id order B's first write is SEAT_1, so B queues behind A without touching SEAT_2.
        // Then A is let go: it writes SEAT_2 and commits; B loses its version check on SEAT_1,
        // retries and reads SEAT_1 as HELD. If B wrote in request order it would lock SEAT_2 first:
        // A would then wait for B's SEAT_2 while B waits for A's SEAT_1, and Postgres would kill one
        // with 40P01 (deadlock detected), which fails this test.
        jdbc.sql("""
                CREATE FUNCTION test_pause_after_seat_update() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN PERFORM pg_advisory_xact_lock(4242); RETURN NULL; END $$
                """).update();
        jdbc.sql("CREATE TRIGGER test_pause_seat_1 AFTER UPDATE ON event_seats FOR EACH ROW "
                + "WHEN (NEW.seat_id = '" + SEAT_1 + "') EXECUTE FUNCTION test_pause_after_seat_update()").update();
        try (Connection gate = openTransaction()) {
            exec(gate, "SELECT pg_advisory_lock(4242)");  // session-level: held until unlocked
            long conflicts = count(HoldMetrics.CONFLICTS);
            UUID holdA = UUID.randomUUID();
            UUID holdB = UUID.randomUUID();

            Future<HoldOutcome> a = THREADS.submit(() -> strategy.hold(request(holdA, EVENT_A, SEAT_1, SEAT_2)));
            waitUntilSessionsAreBlockedOnALock(1);  // A: wrote SEAT_1, waiting in the trigger
            Future<HoldOutcome> b = THREADS.submit(
                    () -> strategy.hold(requestBy(OTHER_USER, holdB, EVENT_A, SEAT_2, SEAT_1)));
            waitUntilSessionsAreBlockedOnALock(2);  // B: waiting for a row lock A holds
            exec(gate, "SELECT pg_advisory_unlock(4242)");

            HoldOutcome outcomeA = a.get(30, TimeUnit.SECONDS);  // an ExecutionException here = 40P01
            HoldOutcome outcomeB = b.get(30, TimeUnit.SECONDS);

            assertThat(outcomeA).isInstanceOf(Held.class);
            assertSeatTaken(outcomeB, SEAT_1, SEAT_2);
            assertThat(count(HoldMetrics.CONFLICTS) - conflicts).as("B lost once, then saw the seats taken")
                    .isEqualTo(1);
            assertThat(seat(EVENT_A, SEAT_1)).isEqualTo(new SeatRow(SeatStatus.HELD, holdA, null, 1));
            assertThat(seat(EVENT_A, SEAT_2)).isEqualTo(new SeatRow(SeatStatus.HELD, holdA, null, 1));
            assertThat(hold(holdB)).isEmpty();
        } finally {
            jdbc.sql("DROP TRIGGER test_pause_seat_1 ON event_seats").update();
            jdbc.sql("DROP FUNCTION test_pause_after_seat_update()").update();
        }
    }

    @Test
    void theSeatReadTakesNoRowLock() throws Exception {
        // FOR KEY SHARE (what a foreign-key check takes) conflicts with SELECT ... FOR UPDATE, but not
        // with a plain UPDATE that leaves the key columns alone. So with another session holding
        // FOR KEY SHARE on SEAT_1, a strategy that read with FOR UPDATE would block; the optimistic
        // strategy, which only reads and then UPDATEs, must not.
        try (Connection other = openTransaction()) {
            exec(other, "SELECT 1 FROM event_seats WHERE event_id = ? AND seat_id = ? FOR KEY SHARE", EVENT_A, SEAT_1);

            Future<HoldOutcome> pending = THREADS.submit(() -> strategy.hold(request(UUID.randomUUID(), EVENT_A, SEAT_1)));

            assertThat(pending.get(10, TimeUnit.SECONDS)).isInstanceOf(Held.class);
            other.rollback();
        }
    }

    // --- transactions and counters ------------------------------------------------------------------

    @Test
    void holdRefusesToJoinACallersTransaction() {
        // D-015: callers must not be @Transactional. Each try needs its own transaction to roll back,
        // so Propagation.NEVER turns a caller's transaction into an error instead of joining it.
        TransactionTemplate caller = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> caller.executeWithoutResult(
                status -> strategy.hold(request(UUID.randomUUID(), EVENT_A, SEAT_1))))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(seat(EVENT_A, SEAT_1)).isEqualTo(SeatRow.FRESH);
    }

    @Test
    void metersAreTaggedOnlyByStrategyAndOutcome() {
        long held = outcomes("held");

        strategy.hold(request(UUID.randomUUID(), EVENT_A, SEAT_1));

        assertThat(outcomes("held") - held).isEqualTo(1);
        // Bounded series (D-021): never a seat, hold, user or event id as a tag.
        for (Meter meter : meters.getMeters()) {
            if (meter.getId().getName().startsWith("stampede.inventory.hold")) {
                assertThat(meter.getId().getTags()).extracting(Tag::getKey)
                        .as(meter.getId().getName()).isSubsetOf("strategy", "outcome");
            }
        }
    }

    // --- helpers -------------------------------------------------------------------------------------

    /** The optimistic strategy's counter so far (0 before its first increment). */
    private long count(String name) {
        var counter = meters.find(name).tag("strategy", "OPTIMISTIC").counter();
        return counter == null ? 0 : (long) counter.count();
    }

    /** How many optimistic hold calls ended with this outcome label so far. */
    private long outcomes(String outcome) {
        var timer = meters.find(HoldMetrics.DURATION).tag("strategy", "OPTIMISTIC").tag("outcome", outcome).timer();
        return timer == null ? 0 : timer.count();
    }

    /** What another buyer's committed hold writes, but left uncommitted in {@code connection}. */
    private static void holdInOpenTransaction(Connection connection, UUID holdId, UUID user, UUID seat)
            throws Exception {
        exec(connection, "INSERT INTO holds (id, event_id, user_id, seat_ids, expires_at, status) "
                + "VALUES (?, ?, ?, ?, now() + interval '5 minutes', 'ACTIVE')", holdId, EVENT_A, user, new UUID[] {seat});
        exec(connection, "INSERT INTO user_event_allowance (event_id, user_id, used) VALUES (?, ?, 1)", EVENT_A, user);
        exec(connection, "UPDATE event_seats SET status = 'HELD', hold_id = ?, version = version + 1 "
                + "WHERE event_id = ? AND seat_id = ?", holdId, EVENT_A, seat);
    }
}
