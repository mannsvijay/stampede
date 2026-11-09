package com.stampede.inventory.strategy;

import com.stampede.inventory.seat.SeatTransition;

/**
 * Why a hold is being released. Picks the seat transition and the hold's final status
 * ({@code ck_holds_status} has no separate value for a failed saga, so that is RELEASED).
 */
public enum ReleaseReason {
    /** The buyer cancelled ({@code DELETE /holds/{id}}). */
    RELEASED(SeatTransition.RELEASE, "RELEASED"),
    /** The hold ran out (expiry sweeper). */
    EXPIRED(SeatTransition.EXPIRE, "EXPIRED"),
    /** Checkout failed or timed out. */
    SAGA_FAILED(SeatTransition.FAIL_SAGA, "RELEASED");

    private final SeatTransition seatTransition;
    private final String holdStatus;

    ReleaseReason(SeatTransition seatTransition, String holdStatus) {
        this.seatTransition = seatTransition;
        this.holdStatus = holdStatus;
    }

    /** The EventSeat transition to apply to each seat of the hold. */
    public SeatTransition seatTransition() {
        return seatTransition;
    }

    /** The value to write to {@code holds.status}. */
    public String holdStatus() {
        return holdStatus;
    }
}
