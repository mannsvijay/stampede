package com.stampede.inventory.seat;

/**
 * Every seat transition drawn in PRD section 2 (narrowed by D-003 and D-014). Anything not listed
 * here, or attempted from the wrong state, is rejected by {@link EventSeat}.
 */
public enum SeatTransition {
    /** AVAILABLE to HELD: a hold takes the seat. */
    HOLD,
    /** HELD to AVAILABLE: the buyer cancels their hold ({@code DELETE /holds/{id}}). */
    RELEASE,
    /** HELD to AVAILABLE: the hold ran out (expiry sweeper, PRD 6.3). */
    EXPIRE,
    /** HELD to AVAILABLE: checkout failed or timed out (saga "SeatsReleased", PRD 6.4). */
    FAIL_SAGA,
    /** HELD to SOLD: payment confirmed; the order now owns the seat. */
    SELL
}
