package com.stampede.inventory.strategy;

/**
 * Why a hold is being released. Picks the seat transition (EventSeat release, expire or failSaga) and
 * the hold's final status ({@code ck_holds_status} has no separate value for a failed saga, so that
 * is RELEASED).
 */
public enum ReleaseReason {
    /** The buyer cancelled ({@code DELETE /holds/{id}}). */
    RELEASED("RELEASED"),
    /** The hold ran out (expiry sweeper). */
    EXPIRED("EXPIRED"),
    /** Checkout failed or timed out. */
    SAGA_FAILED("RELEASED");

    private final String holdStatus;

    ReleaseReason(String holdStatus) {
        this.holdStatus = holdStatus;
    }

    /** The value to write to {@code holds.status}. */
    public String holdStatus() {
        return holdStatus;
    }
}
