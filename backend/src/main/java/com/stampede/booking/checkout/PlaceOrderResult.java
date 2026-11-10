package com.stampede.booking.checkout;

import java.util.List;
import java.util.UUID;

/** The result of {@link CheckoutService#placeOrder}. Unexpected failures are exceptions (500). */
sealed interface PlaceOrderResult {

    /** This request created the order and it is PAID. */
    record Placed(OrderView order) implements PlaceOrderResult {
    }

    /**
     * The hold already has an order (a retry, or a concurrent request that won the race); that order
     * is returned as it is now, possibly still PENDING (D-017, option 4A).
     */
    record Existing(OrderView order) implements PlaceOrderResult {
    }

    /** No hold with this id belongs to the caller. Nothing was written. */
    record HoldNotFound() implements PlaceOrderResult {
    }

    /** The hold expired or was released. Nothing is sold; any payment was refunded. */
    record HoldExpired() implements PlaceOrderResult {
    }

    /** The provider declined. The order is CANCELLED and the hold's seats are released. */
    record PaymentDeclined(String reason) implements PlaceOrderResult {
    }

    /**
     * A seat of the hold is still in another order's active items: a checkout still in flight on a
     * hold that has ended, or one that crashed before it could compensate (D-017 known limits).
     * Nothing was written.
     */
    record SeatTaken(List<UUID> seatIds) implements PlaceOrderResult {
        public SeatTaken {
            seatIds = List.copyOf(seatIds);
        }
    }
}
