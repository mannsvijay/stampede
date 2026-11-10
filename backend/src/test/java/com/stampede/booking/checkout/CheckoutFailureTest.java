package com.stampede.booking.checkout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.stampede.inventory.api.HoldService;
import com.stampede.payments.provider.PaymentProvider;
import com.stampede.ticketing.api.TicketIssuer;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * What the checkout does when a step fails after the order exists (D-017's failure table). Spies wrap
 * the real beans, so every test runs the real code except the one injected failure. The rule under
 * test: every way out leaves the order PAID or CANCELLED, never PENDING with seats nobody can order,
 * and once the seats are sold nothing is released or refunded.
 */
class CheckoutFailureTest extends CheckoutTestSupport {

    @MockitoSpyBean
    HoldService holds;

    @MockitoSpyBean
    PaymentProvider provider;

    @MockitoSpyBean
    OrderWriter writer;

    @MockitoSpyBean
    TicketIssuer tickets;

    @Test
    void anUnexpectedExceptionInTheSellStepLeavesNoSeatStuck() {
        // The sale's transaction rolls back (the strategy never ran), the request fails with a 500,
        // and the compensation must leave both seats orderable by someone else.
        UUID first = seats.get(0);
        UUID second = seats.get(1);
        UUID holdId = hold(alice, first, second);
        doThrow(new IllegalStateException("injected: sell step failed"))
                .when(holds).sellForOrder(any(), any());

        Response response = postOrder(alice, holdId);

        assertThat(response.status()).isEqualTo(500);
        assertThat(response.code()).isEqualTo("INTERNAL_ERROR");
        UUID orderId = onlyOrderOfEvent();
        assertThat(orderStatus(orderId)).isEqualTo("CANCELLED");
        assertThat(count("SELECT count(*) FROM order_items WHERE order_id = ? AND active", orderId)).isZero();
        assertThat(paymentStatuses(orderId)).as("the charge was refunded").containsExactly("REFUNDED");
        assertThat(holdStatus(holdId)).isEqualTo("RELEASED");
        assertThat(seatStatus(first)).isEqualTo("AVAILABLE");
        assertThat(seatStatus(second)).isEqualTo("AVAILABLE");
        assertThat(used(aliceId)).as("allowance given back").isZero();
        assertInvariants();
        assertNoPendingOrders();

        // Not stuck: another buyer can hold AND order the same seats (the active-items index would
        // reject the order if the cancelled order's items were still active).
        org.mockito.Mockito.reset(holds);
        Response bobsOrder = postOrder(bob, hold(bob, first, second));
        assertThat(bobsOrder.status()).as(bobsOrder.body().toString()).isEqualTo(201);
        assertThat(seatOrder(first)).isEqualTo(orderId(bobsOrder));
        assertInvariants();
    }

    @Test
    void aSellThatCommittedBeforeThrowingFinishesTheOrderInsteadOfUndoingIt() {
        // The sale commits, then the call throws (an answer lost after the commit). The compensation's
        // release finds the hold CONVERTED under its lock, so the order is finished, not refunded.
        UUID holdId = hold(alice, seats.get(0));
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new IllegalStateException("injected: failed after the sale committed");
        }).when(holds).sellForOrder(any(), any());

        Response response = postOrder(alice, holdId);

        assertThat(response.status()).isEqualTo(201);
        assertThat(response.body().path("status").asText()).isEqualTo("PAID");
        assertThat(paymentStatuses(orderId(response))).as("not refunded").containsExactly("SUCCEEDED");
        assertThat(seatOrder(seats.get(0))).isEqualTo(orderId(response));
        assertThat(used(aliceId)).isEqualTo(1);
        assertInvariants();
        assertNoPendingOrders();
    }

    @Test
    void aFailedFinishIsRetriedAndNeverReleasesOrRefunds() {
        UUID holdId = hold(alice, seats.get(0));
        doThrow(new IllegalStateException("injected: markPaid failed once"))
                .doCallRealMethod()
                .when(writer).markPaid(any());

        Response response = postOrder(alice, holdId);

        assertThat(response.status()).isEqualTo(201);
        assertThat(response.body().path("status").asText()).isEqualTo("PAID");
        verify(writer, times(2)).markPaid(any());
        verify(holds, times(0)).releaseForFailedCheckout(any());
        assertThat(paymentStatuses(orderId(response))).containsExactly("SUCCEEDED");
        assertInvariants();
        assertNoPendingOrders();
    }

    @Test
    void aFinishThatKeepsFailingLeavesTheSaleInPlaceAndThe500ForM3() {
        // Seats are sold; undoing that would refund a buyer who has seats. The order stays PENDING
        // with SOLD seats (only its status is wrong) until M3 recovery: the documented known limit.
        UUID holdId = hold(alice, seats.get(0));
        doThrow(new IllegalStateException("injected: markPaid keeps failing")).when(writer).markPaid(any());

        Response response = postOrder(alice, holdId);

        assertThat(response.status()).isEqualTo(500);
        verify(writer, times(CheckoutService.FINISH_ATTEMPTS)).markPaid(any());
        verify(holds, times(0)).releaseForFailedCheckout(any());
        UUID orderId = onlyOrderOfEvent();
        assertThat(orderStatus(orderId)).isEqualTo("PENDING");
        assertThat(seatOrder(seats.get(0))).isEqualTo(orderId);
        assertThat(holdStatus(holdId)).isEqualTo("CONVERTED");
        assertThat(paymentStatuses(orderId)).as("not refunded").containsExactly("SUCCEEDED");
        // Invariants deliberately not asserted: O1 (sold seat in a PAID order) is the violation the
        // M1.7 checker must report here.
    }

    @Test
    void whenTheCompensatingReleaseFailsNothingIsCancelledOrRefunded() {
        // The sell step throws, and so does the release that would tell us whether the sale
        // committed. Not knowing, the checkout must NOT cancel or refund (that could refund a buyer
        // who has seats): the order is left PENDING with its charge for M3 recovery, and it's a 500.
        UUID holdId = hold(alice, seats.get(0));
        doThrow(new IllegalStateException("injected: sell step failed")).when(holds).sellForOrder(any(), any());
        doThrow(new IllegalStateException("injected: release failed")).when(holds).releaseForFailedCheckout(any());

        Response response = postOrder(alice, holdId);

        assertThat(response.status()).isEqualTo(500);
        UUID orderId = onlyOrderOfEvent();
        assertThat(orderStatus(orderId)).isEqualTo("PENDING");
        assertThat(paymentStatuses(orderId)).as("not refunded").containsExactly("SUCCEEDED");
        assertThat(count("SELECT count(*) FROM order_items WHERE order_id = ? AND active", orderId)).isEqualTo(1);
        verify(writer, times(0)).cancel(any());
        // Invariants deliberately not asserted: this is the documented PENDING outcome (D-017).
    }

    @Test
    void aDeclineWhoseCompensationFailsIsA500NotA402() {
        // The 402 says "your seats have been released"; if that didn't happen, don't claim it.
        UUID holdId = hold(alice, seats.get(0));
        doReturn(PaymentProvider.Charge.declined("fake_declined", "card_declined"))
                .when(provider).charge(any(), anyLong());
        doThrow(new IllegalStateException("injected: release failed")).when(holds).releaseForFailedCheckout(any());

        Response response = postOrder(alice, holdId);

        assertThat(response.status()).isEqualTo(500);
        assertThat(orderStatus(onlyOrderOfEvent())).isEqualTo("PENDING");
        assertThat(holdStatus(holdId)).isEqualTo("ACTIVE");
    }

    @Test
    void aDeclinedPaymentCancelsTheOrderAndReleasesTheSeats() {
        UUID holdId = hold(alice, seats.get(0), seats.get(1));
        doReturn(PaymentProvider.Charge.declined("fake_declined", "card_declined"))
                .when(provider).charge(any(), anyLong());

        Response response = postOrder(alice, holdId);

        assertThat(response.status()).isEqualTo(402);
        assertThat(response.code()).isEqualTo("PAYMENT_FAILED");
        UUID orderId = onlyOrderOfEvent();
        assertThat(orderStatus(orderId)).isEqualTo("CANCELLED");
        assertThat(paymentStatuses(orderId)).containsExactly("FAILED");
        assertThat(holdStatus(holdId)).isEqualTo("RELEASED");
        assertThat(seatStatus(seats.get(0))).isEqualTo("AVAILABLE");
        assertThat(used(aliceId)).isZero();
        assertInvariants();
        assertNoPendingOrders();

        org.mockito.Mockito.reset(provider);
        assertThat(postOrder(bob, hold(bob, seats.get(0), seats.get(1))).status()).isEqualTo(201);
    }

    @Test
    void aPaymentProviderThatThrowsLeavesNothingCharged() {
        UUID holdId = hold(alice, seats.get(0));
        doThrow(new IllegalStateException("injected: provider outage")).when(provider).charge(any(), anyLong());

        Response response = postOrder(alice, holdId);

        assertThat(response.status()).isEqualTo(500);
        UUID orderId = onlyOrderOfEvent();
        assertThat(orderStatus(orderId)).isEqualTo("CANCELLED");
        assertThat(paymentStatuses(orderId)).as("outcome unknown: left CREATED for reconciliation (M3)")
                .containsExactly("CREATED");
        assertThat(holdStatus(holdId)).isEqualTo("RELEASED");
        assertThat(seatStatus(seats.get(0))).isEqualTo("AVAILABLE");
        assertInvariants();
        assertNoPendingOrders();
    }

    @Test
    void aHoldThatExpiresDuringPaymentIs410AndTheChargeIsRefunded() {
        // The hold passes the check, then expires while the provider is charging. The sale must
        // refuse it under the hold lock, and the money must go back.
        UUID holdId = hold(alice, seats.get(0));
        doAnswer(invocation -> {
            jdbc.sql("UPDATE holds SET created_at = now() - interval '6 minutes', "
                    + "expires_at = now() - interval '1 minute' WHERE id = ?").param(holdId).update();
            return invocation.callRealMethod();
        }).when(provider).charge(any(), anyLong());

        Response response = postOrder(alice, holdId);

        assertThat(response.status()).isEqualTo(410);
        assertThat(response.code()).isEqualTo("HOLD_EXPIRED");
        UUID orderId = onlyOrderOfEvent();
        assertThat(orderStatus(orderId)).isEqualTo("CANCELLED");
        assertThat(paymentStatuses(orderId)).containsExactly("REFUNDED");
        assertThat(count("SELECT count(*) FROM order_items WHERE order_id = ? AND active", orderId)).isZero();
        // Nothing sold. The hold is left to the expiry sweeper (disabled here), which frees the seat.
        assertThat(seatStatus(seats.get(0))).isEqualTo("HELD");
        assertThat(holdStatus(holdId)).isEqualTo("ACTIVE");
        verify(holds, times(0)).releaseForFailedCheckout(any());
        assertInvariants();
        assertNoPendingOrders();
    }

    // --- step 5, TicketsIssued (M1.6, D-018): the order is PAID, so nothing is ever undone -------

    @Test
    void aTicketIssueThatFailsOnceIsRetried() {
        UUID holdId = hold(alice, seats.get(0), seats.get(1));
        doThrow(new IllegalStateException("injected: issuing failed once"))
                .doCallRealMethod()
                .when(tickets).issueForOrder(any());

        Response response = postOrder(alice, holdId);

        assertThat(response.status()).isEqualTo(201);
        UUID orderId = orderId(response);
        verify(tickets, times(2)).issueForOrder(any());
        assertThat(ticketsOfOrder(orderId)).isEqualTo(2);
        assertThat(sagaState(orderId)).isEqualTo("TICKETS_ISSUED");
        assertInvariants();
        assertNoPendingOrders();
    }

    @Test
    void ticketsThatCannotBeIssuedLeaveThePaidSaleAloneAndARetryIssuesThem() {
        UUID holdId = hold(alice, seats.get(0), seats.get(1));
        doThrow(new IllegalStateException("injected: ticketing down")).when(tickets).issueForOrder(any());

        Response response = postOrder(alice, holdId);

        // The buyer paid and has the seats: still 201 PAID, nothing released or refunded.
        assertThat(response.status()).isEqualTo(201);
        assertThat(response.body().path("status").asText()).isEqualTo("PAID");
        UUID orderId = orderId(response);
        verify(tickets, times(CheckoutService.TICKET_ATTEMPTS)).issueForOrder(any());
        verify(holds, times(0)).releaseForFailedCheckout(any());
        assertThat(ticketsOfOrder(orderId)).isZero();
        assertThat(sagaState(orderId)).as("saga stays at SEATS_SOLD").isEqualTo("SEATS_SOLD");
        assertThat(paymentStatuses(orderId)).as("not refunded").containsExactly("SUCCEEDED");
        assertThat(seatOrder(seats.get(0))).isEqualTo(orderId);
        assertInvariants();
        assertNoPendingOrders();

        // Ticketing is back; the buyer retries the same checkout.
        org.mockito.Mockito.reset(tickets);
        Response retry = postOrder(alice, holdId);

        assertThat(retry.status()).as("the existing order").isEqualTo(200);
        assertThat(orderId(retry)).isEqualTo(orderId);
        assertThat(ticketsOfOrder(orderId)).isEqualTo(2);
        assertThat(sagaState(orderId)).isEqualTo("TICKETS_ISSUED");
        assertThat(paymentStatuses(orderId)).as("charged once").containsExactly("SUCCEEDED");
        assertInvariants();

        // A further retry changes nothing.
        assertThat(postOrder(alice, holdId).status()).isEqualTo(200);
        assertThat(ticketsOfOrder(orderId)).isEqualTo(2);
    }

    @Test
    void aSaleThatCommittedBeforeThrowingStillGetsItsTickets() {
        UUID holdId = hold(alice, seats.get(0));
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new IllegalStateException("injected: failed after the sale committed");
        }).when(holds).sellForOrder(any(), any());

        Response response = postOrder(alice, holdId);

        assertThat(response.status()).isEqualTo(201);
        assertThat(ticketsOfOrder(orderId(response))).isEqualTo(1);
        assertThat(sagaState(orderId(response))).isEqualTo("TICKETS_ISSUED");
        assertInvariants();
    }

    @Test
    void failedCheckoutsIssueNoTickets() {
        doReturn(PaymentProvider.Charge.declined("fake_declined", "card_declined"))
                .when(provider).charge(any(), anyLong());

        assertThat(postOrder(alice, hold(alice, seats.get(0))).status()).isEqualTo(402);

        verify(tickets, times(0)).issueForOrder(any());
        assertThat(ticketsOfEvent()).isZero();
    }

    private UUID onlyOrderOfEvent() {
        return jdbc.sql("SELECT id FROM orders WHERE event_id = ?").param(event).query(UUID.class).single();
    }
}
