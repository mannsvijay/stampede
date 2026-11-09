package com.stampede.inventory.seat;

import static com.stampede.inventory.seat.IllegalSeatTransitionException.Reason.HOLD_MISMATCH;
import static com.stampede.inventory.seat.IllegalSeatTransitionException.Reason.WRONG_STATE;

import java.util.Objects;
import java.util.UUID;

/**
 * One seat for one event: the in-code seat state machine (PRD 2, D-003, D-004, D-014).
 * <p>
 * Immutable. Every transition returns a new {@code EventSeat} with {@code version + 1}; a rejected
 * transition throws {@link IllegalSeatTransitionException} and leaves this object as it was.
 * <p>
 * Two layers of rules:
 * <ul>
 *   <li>The constructor enforces exactly the database CHECK {@code ck_event_seats_status_pointers}, so
 *       any row the database accepts can be loaded, and no seat the database would reject can exist.</li>
 *   <li>Transitions always produce the strict shape: the pointer on a seat is its current owner (the
 *       hold while HELD, the order once SOLD) and nothing else (D-014). The CHECK is looser (it allows
 *       HELD with an order_id, SOLD with a hold_id), but no transition produces those.</li>
 * </ul>
 * Holding several seats all-or-nothing is not this class's job: the inventory strategy applies a
 * transition to every seat of a hold inside one transaction, or to none.
 *
 * @param holdId  owning hold; required when HELD, NULL when AVAILABLE
 * @param orderId owning order; required when SOLD, NULL when AVAILABLE
 * @param version bumped on every transition; the optimistic strategy compares it (M2.1)
 */
public record EventSeat(UUID eventId, UUID seatId, SeatStatus status, UUID holdId, UUID orderId, long version) {

    public EventSeat {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(seatId, "seatId");
        Objects.requireNonNull(status, "status");
        if (version < 0) {
            throw new IllegalArgumentException("version must be >= 0, was " + version);
        }
        // Mirrors ck_event_seats_status_pointers in V1__init.sql, branch for branch.
        boolean pointersMatchStatus = switch (status) {
            case AVAILABLE -> holdId == null && orderId == null;
            case HELD -> holdId != null;
            case SOLD -> orderId != null;
        };
        if (!pointersMatchStatus) {
            throw new IllegalArgumentException("Seat " + seatId + " is " + status
                    + " but hold_id=" + holdId + ", order_id=" + orderId
                    + " (AVAILABLE needs neither, HELD needs a hold, SOLD needs an order)");
        }
    }

    /** A fresh seat nobody owns, as the seed loader inserts it. */
    public static EventSeat available(UUID eventId, UUID seatId) {
        return new EventSeat(eventId, seatId, SeatStatus.AVAILABLE, null, null, 0);
    }

    /** AVAILABLE to HELD. */
    public EventSeat hold(UUID holdId) {
        Objects.requireNonNull(holdId, "holdId");
        if (status != SeatStatus.AVAILABLE) {
            throw new IllegalSeatTransitionException(seatId, status, SeatTransition.HOLD, WRONG_STATE,
                    "Seat " + seatId + " cannot HOLD from " + status);
        }
        return next(SeatStatus.HELD, holdId, null);
    }

    /** HELD to AVAILABLE because the buyer released the hold. */
    public EventSeat release(UUID holdId) {
        return free(SeatTransition.RELEASE, holdId);
    }

    /** HELD to AVAILABLE because the hold expired. */
    public EventSeat expire(UUID holdId) {
        return free(SeatTransition.EXPIRE, holdId);
    }

    /** HELD to AVAILABLE because checkout failed. */
    public EventSeat failSaga(UUID holdId) {
        return free(SeatTransition.FAIL_SAGA, holdId);
    }

    /** HELD to SOLD. The order becomes the owner and the hold pointer is cleared (D-014). */
    public EventSeat sell(UUID holdId, UUID orderId) {
        Objects.requireNonNull(orderId, "orderId");
        requireHeldBy(SeatTransition.SELL, holdId);
        return next(SeatStatus.SOLD, null, orderId);
    }

    private EventSeat free(SeatTransition transition, UUID holdId) {
        requireHeldBy(transition, holdId);
        return next(SeatStatus.AVAILABLE, null, null);
    }

    /** The guard shared by every transition out of HELD: the seat must be HELD, by this hold. */
    private void requireHeldBy(SeatTransition transition, UUID holdId) {
        Objects.requireNonNull(holdId, "holdId");
        if (status != SeatStatus.HELD) {
            throw new IllegalSeatTransitionException(seatId, status, transition, WRONG_STATE,
                    "Seat " + seatId + " cannot " + transition + " from " + status);
        }
        if (!holdId.equals(this.holdId)) {
            throw new IllegalSeatTransitionException(seatId, status, transition, HOLD_MISMATCH,
                    "Seat " + seatId + " cannot " + transition + ": it is held by hold " + this.holdId
                            + ", not " + holdId);
        }
    }

    private EventSeat next(SeatStatus newStatus, UUID newHoldId, UUID newOrderId) {
        return new EventSeat(eventId, seatId, newStatus, newHoldId, newOrderId, version + 1);
    }
}
