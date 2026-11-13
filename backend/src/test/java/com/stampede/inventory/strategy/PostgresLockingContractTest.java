package com.stampede.inventory.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stampede.inventory.seat.SeatStatus;
import com.stampede.inventory.strategy.HoldOutcome.Held;
import com.stampede.inventory.strategy.HoldOutcome.Rejected;
import com.stampede.inventory.strategy.ReleaseOutcome.NotActive;
import com.stampede.inventory.strategy.ReleaseOutcome.NotExpired;
import com.stampede.inventory.strategy.ReleaseOutcome.NotFound;
import com.stampede.inventory.strategy.ReleaseOutcome.Released;
import com.stampede.testsupport.ExpectInvariantViolations;
import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * The part of the contract only the Postgres strategies (pessimistic and optimistic) share: tests that
 * set up holds and sales with plain SQL ({@link #holdDirectly}, {@link #sellDirectly}), so release and
 * sell don't depend on {@code hold()} being correct; tests that change a hold, an event or an allowance
 * row in Postgres behind the strategy's back; and the lock-order tests, which observe Postgres row locks.
 * Redis-first decides in Redis, so none of this setup reaches it; {@link RedisFirstInventoryStrategyTest}
 * covers the same behaviours through its real paths. Split from {@link InventoryStrategyContractTest}
 * in M2.2c, every test moved unchanged.
 * <p>
 * The lock tests hold for both strategies because they observe rows, not SQL: the pessimistic
 * strategy locks seats with {@code SELECT ... FOR UPDATE}, the optimistic one with its version-checked
 * UPDATEs, and both in Postgres {@code seat_id} order.
 */
abstract class PostgresLockingContractTest extends InventoryStrategyContractTest {

    // --- hold -------------------------------------------------------------------------------

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

    // --- release ----------------------------------------------------------------------------

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

    // --- takenAgoMs (D-016) -----------------------------------------------------------------

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

    // --- per-user cap (D-005, M1.4) ---------------------------------------------------------

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

    // --- races (small; the 1000-run harness is M1.3) ----------------------------------------

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

    // --- sell (M1.5, D-017) -----------------------------------------------------------------

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

    // Sell and cancel of the same hold take turns: both lock the hold row first, so whichever comes
    // second waits, then sees the status the first one wrote. Never sold AND released. The order is
    // fixed with a blocking session (inTurns), not left to a race: the two orders end in different
    // states, so they are two tests, each with its exact expected invariant rules.

    @Test
    @ExpectInvariantViolations(rules = {"O1", "O5", "O7"},
            reason = "Strategy unit test of step 3 only (D-017): the order is a bare PENDING row and step 4 (booking marks it PAID) never runs, so its seats are SOLD to a PENDING order")
    void sellThenCancelOfTheSameHoldTakeTurns() throws Exception {
        UUID holdId = UUID.randomUUID();
        holdDirectly(holdId, EVENT_A, SEAT_1, SEAT_2);
        UUID orderId = pendingOrder(holdId, EVENT_A);

        List<Object> outcomes = inTurns(holdId,
                () -> strategy.sell(EVENT_A, holdId, orderId),
                () -> strategy.release(EVENT_A, holdId, ReleaseReason.RELEASED));

        assertThat(outcomes.get(0)).isInstanceOf(SellOutcome.Sold.class);
        assertThat(outcomes.get(1)).as("cancel waited, then saw the sale").isEqualTo(new NotActive("CONVERTED"));
        assertThat(seat(EVENT_A, SEAT_1)).isEqualTo(new SeatRow(SeatStatus.SOLD, null, orderId, 2));
        assertThat(seat(EVENT_A, SEAT_2)).isEqualTo(new SeatRow(SeatStatus.SOLD, null, orderId, 2));
        assertThat(hold(holdId).orElseThrow().status()).isEqualTo("CONVERTED");
        assertThat(used(EVENT_A)).isEqualTo(2);
    }

    @Test
    @ExpectInvariantViolations(rules = {"O7"},
            reason = "The refused sale leaves the bare PENDING order this strategy unit test inserted; step 4 is booking's and never runs")
    void cancelThenSellOfTheSameHoldTakeTurns() throws Exception {
        UUID holdId = UUID.randomUUID();
        holdDirectly(holdId, EVENT_A, SEAT_1, SEAT_2);
        UUID orderId = pendingOrder(holdId, EVENT_A);

        List<Object> outcomes = inTurns(holdId,
                () -> strategy.release(EVENT_A, holdId, ReleaseReason.RELEASED),
                () -> strategy.sell(EVENT_A, holdId, orderId));

        assertThat(outcomes.get(0)).isInstanceOf(Released.class);
        assertThat(outcomes.get(1)).as("sell waited, then saw the release")
                .isEqualTo(new SellOutcome.NotActive("RELEASED"));
        assertThat(seat(EVENT_A, SEAT_1)).isEqualTo(new SeatRow(SeatStatus.AVAILABLE, null, null, 2));
        assertThat(seat(EVENT_A, SEAT_2)).isEqualTo(new SeatRow(SeatStatus.AVAILABLE, null, null, 2));
        assertThat(hold(holdId).orElseThrow().status()).isEqualTo("RELEASED");
        assertThat(used(EVENT_A)).isEqualTo(0);
    }

    // --- helpers ---------------------------------------------------------------------------------

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
}
