package com.stampede.booking.checkout;

import static org.assertj.core.api.Assertions.assertThat;

import com.stampede.ticketing.api.IssueTickets;
import com.stampede.ticketing.api.TicketIssuer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Idempotent, all-or-nothing issuing under concurrency (D-018). The referee is
 * {@code uq_tickets_live_seat} with {@code ON CONFLICT DO NOTHING}: a second insert for a seat waits
 * for the first transaction, then does nothing (same order) or makes the later check fail (another
 * order).
 * <ul>
 *   <li>{@link #aSecondIssuerWaitsForTheFirstAndThenAddsNothing} proves the waiting happens:
 *       deterministic, with the first transaction held open and the second seen blocked in
 *       {@code pg_locks}.</li>
 *   <li>The racing tests release all threads together with a barrier, for
 *       {@code -Dstampede.harness.rounds} rounds (default 30), like the inventory harness. There is no
 *       randomness in them, so there is no seed to replay.</li>
 * </ul>
 * Thread counts stay below Hikari's default pool of 10 connections, so every thread can hold a
 * connection at once and really races instead of queueing for one.
 */
class TicketIssuingConcurrencyTest extends CheckoutTestSupport {

    private static final int THREADS = 8;
    private static final int ROUNDS = Integer.getInteger("stampede.harness.rounds", 30);
    /** The HTTP race is slower (8 requests per round, each through the whole checkout); fewer rounds. */
    private static final int HTTP_ROUNDS = Math.max(1, ROUNDS / 3);
    private static final int SEATS_PER_ORDER = 4;
    private static final ExecutorService POOL = Executors.newFixedThreadPool(THREADS);

    @Autowired
    TicketIssuer issuer;

    @Autowired
    PlatformTransactionManager transactions;

    private UUID holdId;
    private UUID orderId;
    private List<UUID> orderSeats;

    @AfterAll
    static void stopThreads() {
        POOL.shutdownNow();
    }

    @BeforeEach
    void sellOneOrder() {
        orderSeats = seats.subList(0, SEATS_PER_ORDER);
        holdId = hold(alice, orderSeats.toArray(UUID[]::new));
        Response response = postOrder(alice, holdId);
        assertThat(response.status()).isEqualTo(201);
        orderId = orderId(response);
    }

    @Test
    void aSecondIssuerWaitsForTheFirstAndThenAddsNothing() throws Exception {
        forgetTickets(orderId);
        IssueTickets command = command(orderId, aliceId, orderSeats);
        CountDownLatch firstInserted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);

        // The first issuer inserts its tickets and keeps its transaction open.
        Future<?> first = POOL.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
            issuer.issueForOrder(command);
            firstInserted.countDown();
            await(releaseFirst);
        }));
        assertThat(firstInserted.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(committedTickets()).as("the first issuer's tickets aren't committed yet").isEmpty();

        Future<?> second = POOL.submit(() -> issuer.issueForOrder(command));

        // The second must be blocked on the first's uncommitted index entries, not finished.
        waitUntilABackendWaitsForALock();
        assertThat(second.isDone()).as("second issuer finished while the first was still open").isFalse();

        releaseFirst.countDown();
        first.get(10, TimeUnit.SECONDS);
        second.get(10, TimeUnit.SECONDS);  // completes without an exception: it did nothing

        assertOneTicketPerSeat(orderId, orderSeats, 0);
        recordTicketsIssued(orderId);
        assertInvariants();
    }

    @Test
    void concurrentIssuingOfOneOrderCreatesExactlyOneTicketPerSeat() throws Exception {
        IssueTickets command = command(orderId, aliceId, orderSeats);
        for (int round = 0; round < ROUNDS; round++) {
            forgetTickets(orderId);

            race(THREADS, i -> {
                issuer.issueForOrder(command);
                return null;
            });

            assertOneTicketPerSeat(orderId, orderSeats, round);
        }
        recordTicketsIssued(orderId);
        assertInvariants();
    }

    @Test
    void twoOrdersRacingForOneSeatLeaveAllOfOneAndNoneOfTheOther() throws Exception {
        // Unreachable through checkout (uq_order_items_active_seat stops a second order for a seat),
        // but it is TicketIssuer's own guarantee: called directly, a cross-order conflict is all or
        // nothing. Bob's real order is seat 5; his command also claims Alice's seat 0 and the free seat 6.
        UUID seat = orderSeats.get(0);
        UUID free = seats.get(6);
        UUID bobOrder = orderId(postOrder(bob, hold(bob, seats.get(5))));
        IssueTickets alices = command(orderId, aliceId, List.of(seat));
        IssueTickets bobs = command(bobOrder, bobId, List.of(seat, free));
        int aliceWins = 0;
        // Alice's command is one seat to Bob's two, so released together she nearly always wins.
        // In odd rounds Alice starts 5 ms late, so both outcomes (and both losers) get exercised.
        int rounds = Math.max(2, ROUNDS);
        for (int round = 0; round < rounds; round++) {
            forgetTickets(orderId);
            forgetTickets(bobOrder);
            boolean aliceLate = round % 2 == 1;

            List<Boolean> succeeded = race(2, i -> {
                if (i == 0 && aliceLate) {
                    Thread.sleep(5);
                }
                try {
                    issuer.issueForOrder(i == 0 ? alices : bobs);
                    return true;
                } catch (IllegalStateException e) {
                    assertThat(e).hasMessageContaining("already have a live ticket from another order");
                    return false;
                }
            });

            assertThat(succeeded).as("round %d: exactly one order wins", round).containsExactlyInAnyOrder(true, false);
            if (succeeded.get(0)) {
                aliceWins++;
                assertOneTicketPerSeat(orderId, List.of(seat), round);
                assertThat(ticketsOfOrder(bobOrder)).as("round %d: loser wrote nothing", round).isZero();
            } else {
                assertOneTicketPerSeat(bobOrder, List.of(seat, free), round);
                assertThat(ticketsOfOrder(orderId)).as("round %d: loser wrote nothing", round).isZero();
            }
        }
        System.out.printf("two-order race: alice won %d of %d rounds%n", aliceWins, rounds);
        assertThat(aliceWins).as("both outcomes happened").isBetween(1, rounds - 1);
        // Bob's command was not a real sale: give both real orders their real tickets back and check.
        forgetTickets(orderId);
        forgetTickets(bobOrder);
        issuer.issueForOrder(command(orderId, aliceId, orderSeats));
        issuer.issueForOrder(command(bobOrder, bobId, List.of(seats.get(5))));
        recordTicketsIssued(orderId);
        recordTicketsIssued(bobOrder);
        assertInvariants();
    }

    @Test
    void concurrentRetriesOfACheckoutWithoutTicketsIssueThemOnce() throws Exception {
        for (int round = 0; round < HTTP_ROUNDS; round++) {
            forgetTickets(orderId);

            List<Integer> statuses = race(THREADS, i -> postOrder(alice, holdId).status());

            assertThat(statuses).as("round %d: every retry gets the existing order", round).containsOnly(200);
            assertOneTicketPerSeat(orderId, orderSeats, round);
            assertThat(sagaState(orderId)).isEqualTo("TICKETS_ISSUED");
        }
        assertInvariants();
    }

    // --- helpers ---------------------------------------------------------------------------------

    /** As if step 5 had never succeeded: no tickets, saga back at SEATS_SOLD. */
    private void forgetTickets(UUID order) {
        jdbc.sql("DELETE FROM tickets WHERE order_id = ?").param(order).update();
        jdbc.sql("UPDATE orders SET saga_state = 'SEATS_SOLD' WHERE id = ?").param(order).update();
    }

    /**
     * These tests call {@link TicketIssuer} directly, which issues but doesn't touch booking's saga
     * state; booking's {@code TicketStep} records it. Do that here before checking invariants (T3).
     */
    private void recordTicketsIssued(UUID order) {
        jdbc.sql("UPDATE orders SET saga_state = 'TICKETS_ISSUED' WHERE id = ?").param(order).update();
    }

    private IssueTickets command(UUID order, UUID holder, List<UUID> seatIds) {
        return new IssueTickets(order, event, holder, seatIds.stream()
                .map(seatId -> new IssueTickets.Seat(seatId, "A", "A", seats.indexOf(seatId) + 1))
                .toList());
    }

    private void assertOneTicketPerSeat(UUID order, List<UUID> expectedSeats, int round) {
        assertThat(jdbc.sql("SELECT seat_id FROM tickets WHERE order_id = ? AND status <> 'REVOKED'")
                .param(order).query(UUID.class).list())
                .as("round %d: exactly one live ticket per seat", round)
                .containsExactlyInAnyOrderElementsOf(expectedSeats);
    }

    /** The order's tickets as another connection sees them (only committed rows). */
    private List<UUID> committedTickets() {
        return jdbc.sql("SELECT id FROM tickets WHERE order_id = ?").param(orderId).query(UUID.class).list();
    }

    /** Polls pg_locks until some backend in this database is waiting for a lock (5 s at most). */
    private void waitUntilABackendWaitsForALock() throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(5));
        while (Instant.now().isBefore(deadline)) {
            long waiting = jdbc.sql("""
                            SELECT count(*) FROM pg_locks l JOIN pg_stat_activity a ON a.pid = l.pid
                            WHERE NOT l.granted AND a.datname = current_database()
                            """)
                    .query(Long.class).single();
            if (waiting > 0) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("The second issuer never waited for a lock");
    }

    private interface Call<T> {
        T run(int thread) throws Exception;
    }

    /** Runs {@code call} on {@code threads} threads released together; rethrows any thread's failure. */
    private <T> List<T> race(int threads, Call<T> call) throws Exception {
        CyclicBarrier start = new CyclicBarrier(threads);
        List<Future<T>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int thread = i;
            futures.add(POOL.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                return call.run(thread);
            }));
        }
        List<T> results = new ArrayList<>();
        for (Future<T> future : futures) {
            try {
                results.add(future.get(30, TimeUnit.SECONDS));
            } catch (ExecutionException e) {
                throw new AssertionError("A racing thread failed", e.getCause());
            }
        }
        return results;
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting to release the first issuer");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
