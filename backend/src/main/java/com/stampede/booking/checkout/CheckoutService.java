package com.stampede.booking.checkout;

import com.stampede.inventory.api.CheckoutHold;
import com.stampede.inventory.api.CheckoutRelease;
import com.stampede.inventory.api.HoldService;
import com.stampede.inventory.api.SellResult;
import com.stampede.payments.api.ChargeResult;
import com.stampede.payments.api.PaymentService;
import com.stampede.ticketing.api.IssueTickets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * POST /orders, synchronously (M1.5, D-017). The steps are the PRD 6.4 saga's, each in its own
 * transaction, owned by the module whose tables it writes:
 * <ol start="0">
 *   <li>HoldVerified: {@link HoldService#checkoutHold}, an unlocked read.</li>
 *   <li>OrderCreated: {@link OrderWriter#createPending}, the PENDING order and its active items.</li>
 *   <li>Payment: {@link PaymentService#charge}.</li>
 *   <li>SeatsSold: {@link HoldService#sellForOrder}, in the inventory strategy's transaction.</li>
 *   <li>PAID: {@link OrderWriter#markPaid}.</li>
 *   <li>TicketsIssued: {@link TicketStep#issue}, one booking transaction that locks the order, issues
 *       through {@code ticketing::api} and records TICKETS_ISSUED (M1.6, D-018). The order is already PAID, so a failure here never undoes anything: the order
 *       stays PAID at saga state SEATS_SOLD, and a retried POST /orders for the hold issues the tickets.</li>
 * </ol>
 * <b>Deliberately not {@code @Transactional}</b> (D-015): the strategy must run in its own transaction,
 * the provider call must not hold a database transaction open, and M3 will persist progress between
 * these same steps.
 * <p>
 * <b>The rule after step 1:</b> an order with active items exists, and those items block their seats
 * from every other order ({@code uq_order_items_active_seat}). So every way out of this method must
 * leave the order PAID (seats SOLD) or CANCELLED (items inactive), never PENDING. Expected outcomes
 * and unexpected exceptions both get best-effort compensation here; only a real process crash between
 * steps can leave a PENDING order behind, which M3's saga recovery fixes (D-017 known limit).
 * <p>
 * Compensation releases the hold <b>first</b>. That release takes the hold row lock, the same lock
 * the sale takes, so its answer is definitive: if the hold turns out CONVERTED, the sale committed
 * and this finishes the order instead of undoing anything.
 */
@Service
class CheckoutService {

    private static final Logger log = LoggerFactory.getLogger(CheckoutService.class);

    /** Attempts at step 4 before giving up (the seats are sold by then; only the status is missing). */
    static final int FINISH_ATTEMPTS = 3;

    /** Attempts at step 5 before giving up (the order is PAID by then; only its tickets are missing). */
    static final int TICKET_ATTEMPTS = 3;

    private final HoldService holds;
    private final PaymentService payments;
    private final OrderWriter writer;
    private final OrderQueries queries;
    private final TicketStep tickets;

    CheckoutService(HoldService holds, PaymentService payments, OrderWriter writer, OrderQueries queries,
                    TicketStep tickets) {
        this.holds = holds;
        this.payments = payments;
        this.writer = writer;
        this.queries = queries;
        this.tickets = tickets;
    }

    PlaceOrderResult placeOrder(UUID userId, UUID holdId) {
        // Step 0: is this the caller's hold, still valid? Nothing written yet, so any exception here
        // (and in step 1, whose transaction rolls back) needs no compensation.
        CheckoutHold.Orderable hold;
        switch (holds.checkoutHold(userId, holdId)) {
            case CheckoutHold.NotFound notFound -> {
                return new PlaceOrderResult.HoldNotFound();
            }
            case CheckoutHold.Expired expired -> {
                return new PlaceOrderResult.HoldExpired();
            }
            case CheckoutHold.Converted converted -> {
                return retriedAfterSale(userId, holdId, converted);
            }
            case CheckoutHold.Orderable orderable -> hold = orderable;
        }

        // Step 1
        UUID orderId = UUID.randomUUID();
        try {
            writer.createPending(orderId, userId, hold);
        } catch (DuplicateKeyException e) {
            if (violated(e, "uq_orders_hold")) {
                // Another request for this hold committed its order first (a retry or a double click).
                return existingOrder(userId, holdId);
            }
            if (violated(e, "uq_order_items_active_seat")) {
                // A seat is still in another order's active items. Either that checkout is still in
                // flight on a hold that has since ended (it will cancel itself at step 3, in seconds),
                // or it crashed before it could compensate (blocked until M3 recovery). The index did
                // its job either way; the buyer can retry shortly.
                log.warn("Hold {}: a seat is still in another order's active items (in-flight or unfinished checkout)",
                        holdId);
                return new PlaceOrderResult.SeatTaken(hold.seats().stream().map(CheckoutHold.CheckoutSeat::seatId)
                        .toList());
            }
            throw e;
        }

        // Step 2. From here on, every exit leaves the order PAID or CANCELLED.
        ChargeResult charge;
        try {
            charge = payments.charge(orderId, hold.totalPaise());
        } catch (RuntimeException e) {
            undo(orderId, holdId, "payment threw", e);
            throw e;
        }
        if (charge instanceof ChargeResult.Declined declined) {
            if (undo(orderId, holdId, "payment declined", null) != Undo.UNDONE) {
                // The 402 promises the seats were released; if the compensation couldn't do that,
                // the honest answer is a 500 (the order is left PENDING, logged by undo()).
                throw new IllegalStateException("Order " + orderId + ": payment declined and compensation failed");
            }
            return new PlaceOrderResult.PaymentDeclined(declined.reason());
        }

        // Step 3
        SellResult sold;
        try {
            sold = holds.sellForOrder(holdId, orderId);
        } catch (RuntimeException e) {
            // Did the sale commit before the exception (for example, the commit succeeded and only
            // the answer was lost)? undo() releases the hold first and finds out under the lock.
            if (undo(orderId, holdId, "sell threw", e) == Undo.SALE_HAD_COMMITTED) {
                finish(orderId);
                issueTickets(orderId, userId, hold.eventId(), hold.seats());
                return placed(userId, orderId);
            }
            throw e;
        }
        if (sold instanceof SellResult.NoLongerHeld gone) {
            // The hold expired or was released while we were paying. Nothing was sold. The hold
            // itself needs no release: the strategy just said it isn't ACTIVE, or it is ACTIVE but past
            // its expiry, which the sweeper closes as EXPIRED (and gives the allowance back).
            log.info("Order {}: hold {} was {} at sale time; refunding", orderId, holdId, gone.holdStatus());
            cancelOrderAndRefund(orderId, "hold " + gone.holdStatus(), null);
            return new PlaceOrderResult.HoldExpired();
        }
        // Sold, or AlreadySold (a previous sale for this same order committed; see SellResult).

        // Step 4
        finish(orderId);
        // Step 5
        issueTickets(orderId, userId, hold.eventId(), hold.seats());
        return placed(userId, orderId);
    }

    /** GET /orders/{id}: only the owner's order; anything else is indistinguishable from unknown. */
    Optional<OrderView> order(UUID userId, UUID orderId) {
        return queries.forUser(userId, orderId);
    }

    private enum Undo { UNDONE, SALE_HAD_COMMITTED, LEFT_PENDING }

    /**
     * Compensation after step 1, for every failure except "hold no longer held at sale time":
     * release the hold (definitive under its lock), then cancel the order and refund. Best effort:
     * a step that throws is logged and attached to {@code cause}, and the remaining steps still run,
     * except that an unknown hold state stops it (see below).
     */
    private Undo undo(UUID orderId, UUID holdId, String why, RuntimeException cause) {
        CheckoutRelease release;
        try {
            release = holds.releaseForFailedCheckout(holdId);
        } catch (RuntimeException releaseFailed) {
            // We don't know whether the hold was sold. Cancelling and refunding a sold order would
            // leave SOLD seats without a PAID order and the buyer refunded: worse than a stuck PENDING
            // order, which M3 recovery (and the M1.7 invariant checker) will find. Stop here.
            attach(cause, releaseFailed);
            log.error("Order {} ({}): could not release hold {}; order left PENDING for M3 recovery",
                    orderId, why, holdId, releaseFailed);
            return Undo.LEFT_PENDING;
        }
        if (release == CheckoutRelease.CONVERTED) {
            log.warn("Order {} ({}): hold {} is CONVERTED, so the sale committed; finishing the order",
                    orderId, why, holdId);
            return Undo.SALE_HAD_COMMITTED;
        }
        cancelOrderAndRefund(orderId, why, cause);
        return Undo.UNDONE;
    }

    /**
     * Cancels the order (items inactive, which unblocks its seats) and then refunds its payments. Each
     * step is attempted even if the other fails; failures are logged with the order id.
     */
    private void cancelOrderAndRefund(UUID orderId, String why, RuntimeException cause) {
        try {
            writer.cancel(orderId);
        } catch (RuntimeException cancelFailed) {
            attach(cause, cancelFailed);
            log.error("Order {} ({}): could not cancel; left PENDING with active items for M3 recovery",
                    orderId, why, cancelFailed);
        }
        try {
            payments.refundOrder(orderId);
        } catch (RuntimeException refundFailed) {
            attach(cause, refundFailed);
            log.error("Order {} ({}): could not refund its payment; needs M3 reconciliation",
                    orderId, why, refundFailed);
        }
    }

    /**
     * Step 4 with retries. The seats are SOLD by now, so the only correct way forward is PAID: never
     * release or refund here. If every attempt fails, the order stays PENDING with SOLD seats (the
     * seats are safe; only its status is wrong) and the exception goes to the caller as a 500.
     */
    private void finish(UUID orderId) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= FINISH_ATTEMPTS; attempt++) {
            try {
                writer.markPaid(orderId);
                return;
            } catch (RuntimeException e) {
                last = e;
                log.warn("Order {}: marking PAID failed (attempt {}/{})", orderId, attempt, FINISH_ATTEMPTS, e);
            }
        }
        log.error("Order {}: seats are SOLD but the order could not be marked PAID; left PENDING for M3 recovery",
                orderId);
        throw last;
    }

    /**
     * Step 5, TicketsIssued (D-018), with retries. The order is PAID: the buyer has paid and has the
     * seats, so nothing here may release, refund or cancel, and the answer stays 201. If every attempt
     * fails, the order stays PAID at saga state SEATS_SOLD with no (or not all) tickets; a retried
     * POST /orders for the same hold runs this again ({@link #retriedAfterSale}), as will M3 recovery.
     * Never throws. Issuing is idempotent, so retrying after a lost answer creates no duplicates.
     */
    private void issueTickets(UUID orderId, UUID userId, UUID eventId, List<CheckoutHold.CheckoutSeat> seats) {
        for (int attempt = 1; attempt <= TICKET_ATTEMPTS; attempt++) {
            try {
                tickets.issue(new IssueTickets(orderId, eventId, userId, seats.stream()
                        .map(seat -> new IssueTickets.Seat(seat.seatId(), seat.section(), seat.row(), seat.number()))
                        .toList()));
                return;
            } catch (RuntimeException e) {
                log.warn("Order {}: issuing tickets failed (attempt {}/{})", orderId, attempt, TICKET_ATTEMPTS, e);
            }
        }
        log.error("Order {}: PAID but its tickets could not be issued; saga left at SEATS_SOLD until a retried "
                + "POST /orders for its hold or M3 recovery issues them", orderId);
    }

    /**
     * POST /orders for a hold that is already CONVERTED: its order exists and its seats are sold. If
     * that checkout's step 5 failed (or is running right now in another request), issue the tickets
     * now. {@code awaitsTickets} is only a cheap pre-check: {@link TicketStep#issue} re-checks the order
     * under its row lock, so racing the original request is harmless. Then 200 with the order.
     */
    private PlaceOrderResult retriedAfterSale(UUID userId, UUID holdId, CheckoutHold.Converted converted) {
        PlaceOrderResult.Existing existing = existingOrder(userId, holdId);
        UUID orderId = existing.order().orderId();
        if (queries.awaitsTickets(orderId)) {
            log.info("Order {}: retried checkout found it PAID without tickets; issuing them", orderId);
            issueTickets(orderId, userId, converted.eventId(), converted.seats());
        }
        return existing;
    }

    private PlaceOrderResult.Existing existingOrder(UUID userId, UUID holdId) {
        return new PlaceOrderResult.Existing(queries.byHold(userId, holdId)
                .orElseThrow(() -> new IllegalStateException("Hold " + holdId + " has an order, but not this user's")));
    }

    private PlaceOrderResult placed(UUID userId, UUID orderId) {
        return new PlaceOrderResult.Placed(queries.forUser(userId, orderId)
                .orElseThrow(() -> new IllegalStateException("Order " + orderId + " vanished after checkout")));
    }

    /** Whether this unique violation is on the named constraint (Postgres names it in the message). */
    private static boolean violated(DuplicateKeyException e, String constraint) {
        String message = e.getMostSpecificCause().getMessage();
        return message != null && message.contains("\"" + constraint + "\"");
    }

    private static void attach(RuntimeException cause, RuntimeException secondary) {
        if (cause != null) {
            cause.addSuppressed(secondary);
        }
    }
}
