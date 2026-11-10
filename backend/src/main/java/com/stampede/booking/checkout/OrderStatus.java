package com.stampede.booking.checkout;

/**
 * The order state machine ({@code ck_orders_status}). {@link OrderWriter} asks {@link #canMoveTo}
 * before every status change and repeats the expected current status in the UPDATE's WHERE clause.
 */
public enum OrderStatus {
    PENDING, PAID, CANCELLED, REFUNDED;

    /**
     * PENDING to PAID or CANCELLED. PAID to REFUNDED is drawn in PRD 2 but has no code path yet
     * (refunds of sold seats are out of scope, D-014), so it is not allowed here either.
     */
    boolean canMoveTo(OrderStatus next) {
        return this == PENDING && (next == PAID || next == CANCELLED);
    }
}
