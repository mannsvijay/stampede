package com.stampede.inventory.seat;

import java.util.UUID;

/**
 * A transition the seat state machine does not allow. This is a domain exception with no HTTP meaning:
 * the inventory service translates it into an API error (for example SEAT_TAKEN or HOLD_EXPIRED) in
 * M1.4/M1.5, where it knows the request context.
 */
public final class IllegalSeatTransitionException extends RuntimeException {

    /** Why the transition was rejected. */
    public enum Reason {
        /** The seat is not in a state this transition starts from (e.g. HOLD on a SOLD seat). */
        WRONG_STATE,
        /** The seat is HELD, but by a different hold than the one the caller named. */
        HOLD_MISMATCH
    }

    private final UUID seatId;
    private final SeatStatus from;
    private final SeatTransition transition;
    private final Reason reason;

    IllegalSeatTransitionException(UUID seatId, SeatStatus from, SeatTransition transition, Reason reason,
            String message) {
        super(message);
        this.seatId = seatId;
        this.from = from;
        this.transition = transition;
        this.reason = reason;
    }

    public UUID seatId() {
        return seatId;
    }

    public SeatStatus from() {
        return from;
    }

    public SeatTransition transition() {
        return transition;
    }

    public Reason reason() {
        return reason;
    }
}
