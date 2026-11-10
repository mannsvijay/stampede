package com.stampede.booking.checkout;

import static org.assertj.core.api.Assertions.assertThat;

import com.stampede.ticketing.api.IssueTickets;
import com.stampede.ticketing.api.TicketIssuer;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Idempotent issuing under concurrency (D-018): many callers issuing the same order at the same moment
 * still leave exactly one live ticket per seat, and none of them fails. The referee is
 * {@code uq_tickets_live_seat} with {@code ON CONFLICT DO NOTHING}: a second insert for a seat waits
 * for the first transaction and then does nothing.
 * <p>
 * Each round starts from a sold order with its tickets deleted (as if step 5 had failed), so every
 * thread really races to insert. A barrier releases all threads together.
 */
class TicketIssuingConcurrencyTest extends CheckoutTestSupport {

    private static final int THREADS = 16;
    private static final int ROUNDS = 30;
    private static final int SEATS_PER_ORDER = 4;
    private static final ExecutorService POOL = Executors.newFixedThreadPool(THREADS);

    @Autowired
    TicketIssuer issuer;

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
    void concurrentIssuingOfOneOrderCreatesExactlyOneTicketPerSeat() throws Exception {
        IssueTickets command = new IssueTickets(orderId, event, aliceId, orderSeats.stream()
                .map(seatId -> new IssueTickets.Seat(seatId, "A", "A", seats.indexOf(seatId) + 1))
                .toList());
        for (int round = 0; round < ROUNDS; round++) {
            forgetTickets();

            race(() -> issuer.issueForOrder(command));

            assertOneTicketPerSeat(round);
        }
        assertInvariants();
    }

    @Test
    void concurrentRetriesOfACheckoutWithoutTicketsIssueThemOnce() throws Exception {
        for (int round = 0; round < ROUNDS / 3; round++) {
            forgetTickets();

            List<Integer> statuses = race(() -> postOrder(alice, holdId).status());

            assertThat(statuses).as("round %d: every retry gets the existing order", round).containsOnly(200);
            assertOneTicketPerSeat(round);
            assertThat(sagaState(orderId)).isEqualTo("TICKETS_ISSUED");
        }
        assertInvariants();
    }

    /** As if step 5 had never succeeded: no tickets, saga back at SEATS_SOLD. */
    private void forgetTickets() {
        jdbc.sql("DELETE FROM tickets WHERE order_id = ?").param(orderId).update();
        jdbc.sql("UPDATE orders SET saga_state = 'SEATS_SOLD' WHERE id = ?").param(orderId).update();
    }

    private void assertOneTicketPerSeat(int round) {
        assertThat(jdbc.sql("SELECT seat_id FROM tickets WHERE order_id = ? AND status <> 'REVOKED'")
                .param(orderId).query(UUID.class).list())
                .as("round %d: exactly one live ticket per seat", round)
                .containsExactlyInAnyOrderElementsOf(orderSeats);
    }

    private interface Call<T> {
        T run() throws Exception;
    }

    private <T> List<T> race(Call<T> call) throws Exception {
        CyclicBarrier start = new CyclicBarrier(THREADS);
        List<Future<T>> futures = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            futures.add(POOL.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                return call.run();
            }));
        }
        List<T> results = new ArrayList<>();
        for (Future<T> future : futures) {
            results.add(future.get(30, TimeUnit.SECONDS));  // rethrows any thread's exception
        }
        return results;
    }

    private void race(Runnable call) throws Exception {
        race(() -> {
            call.run();
            return null;
        });
    }
}
