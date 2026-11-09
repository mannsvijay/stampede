package com.stampede.inventory.strategy;

/** Why a hold was rejected. Mapped to API error codes by the hold service in M1.4. */
public enum HoldRejection {
    /** At least one seat is HELD or SOLD already (will become SEAT_TAKEN). */
    SEAT_TAKEN,
    /** At least one seat id is not a seat of this event: unknown, or a seat of another event. */
    UNKNOWN_SEAT
}
