package com.stampede.booking.checkout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import com.stampede.inventory.api.HoldService;
import com.stampede.payments.provider.PaymentProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Many POST /orders for the same hold at the same moment (a double click, a retry storm): exactly one
 * order, one payment, seats sold once, and every request answered with that same order (D-017).
 * <p>
 * The overlap is <b>forced</b>, not hoped for: a barrier holds every request right after step 0 (the
 * unlocked hold check) until all of them have passed it, so all of them see the hold ACTIVE and all
 * try to create the order. Then {@code uq_orders_hold} alone decides: exactly one insert commits and
 * the rest fail with a duplicate key, which the test counts. Without the barrier, sequential requests
 * would take the CONVERTED path at step 0 and the unique index would never be exercised.
 */
class OrderConcurrencyTest extends CheckoutTestSupport {

    static final int ROUNDS = 10;
    static final int REQUESTS_PER_HOLD = 8;
    static final ExecutorService THREADS = Executors.newFixedThreadPool(REQUESTS_PER_HOLD);

    // The same spy set as CheckoutFailureTest, so both classes share one cached application context.
    @MockitoSpyBean
    HoldService holds;

    @MockitoSpyBean
    PaymentProvider provider;

    @MockitoSpyBean
    OrderWriter writer;

    /** Reusable: trips once all requests of a round have passed step 0, then resets for the next round. */
    private final CyclicBarrier afterStepZero = new CyclicBarrier(REQUESTS_PER_HOLD);
    private final AtomicInteger duplicateOrders = new AtomicInteger();

    @AfterAll
    static void stopThreads() {
        THREADS.shutdownNow();
    }

    @BeforeEach
    void raiseTheCapAndForceTheOverlap() {
        jdbc.sql("UPDATE events SET per_user_limit = ? WHERE id = ?").params(SEAT_COUNT, event).update();
        doAnswer(invocation -> {
            Object checked = invocation.callRealMethod();
            afterStepZero.await(10, TimeUnit.SECONDS);
            return checked;
        }).when(holds).checkoutHold(any(), any());
        doAnswer(invocation -> {
            try {
                return invocation.callRealMethod();
            } catch (DuplicateKeyException e) {
                duplicateOrders.incrementAndGet();
                throw e;
            }
        }).when(writer).createPending(any(), any(), any());
    }

    @Test
    void concurrentOrdersForOneHoldCreateExactlyOneOrder() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            UUID first = seats.get(2 * round);
            UUID second = seats.get(2 * round + 1);
            UUID holdId = hold(alice, first, second);
            duplicateOrders.set(0);

            List<Response> responses = race(holdId);

            assertThat(duplicateOrders.get()).as("round %d: every other request lost on uq_orders_hold", round)
                    .isEqualTo(REQUESTS_PER_HOLD - 1);

            List<Integer> statuses = responses.stream().map(Response::status).toList();
            assertThat(statuses).as("round %d", round).allMatch(s -> s == 200 || s == 201);
            assertThat(statuses).as("round %d: exactly one request created the order", round)
                    .filteredOn(s -> s == 201).hasSize(1);
            List<UUID> orderIds = responses.stream().map(CheckoutTestSupport::orderId).distinct().toList();
            assertThat(orderIds).as("round %d: every response names the same order", round).hasSize(1);
            UUID orderId = orderIds.get(0);

            assertThat(count("SELECT count(*) FROM orders WHERE hold_id = ?", holdId)).isEqualTo(1);
            assertThat(paymentStatuses(orderId)).as("round %d: charged once", round).containsExactly("SUCCEEDED");
            assertThat(orderStatus(orderId)).isEqualTo("PAID");
            assertThat(seatOrder(first)).isEqualTo(orderId);
            assertThat(seatOrder(second)).isEqualTo(orderId);
            assertInvariants();
        }
        assertThat(ordersOfEvent()).isEqualTo(ROUNDS);
        assertThat(used(aliceId)).isEqualTo(2 * ROUNDS);
        assertNoPendingOrders();
    }

    /** Fires REQUESTS_PER_HOLD POSTs for the hold at once and returns every response. */
    private List<Response> race(UUID holdId) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Response>> futures = new ArrayList<>();
        for (int i = 0; i < REQUESTS_PER_HOLD; i++) {
            futures.add(THREADS.submit(() -> {
                start.await();
                return postOrder(alice, holdId);
            }));
        }
        start.countDown();
        List<Response> responses = new ArrayList<>();
        for (Future<Response> future : futures) {
            responses.add(future.get(30, TimeUnit.SECONDS));
        }
        return responses;
    }
}
