package com.stampede.inventory.strategy;

/**
 * Why a hold was rejected, in precedence order: a request with an unknown seat is malformed whatever
 * else is in it, a taken seat is reported before a lost race, and the per-user cap is only checked
 * once every seat could be held (D-015, D-016, D-021). Mapped to API error codes by the hold service.
 */
public enum HoldRejection {
    /** At least one seat id is not a seat of this event: unknown, or a seat of another event. */
    UNKNOWN_SEAT,
    /** At least one seat is HELD or SOLD already (SEAT_TAKEN). */
    SEAT_TAKEN,
    /**
     * The optimistic strategy lost a version check on every try (D-021): other buyers kept changing
     * these seats between its read and its write, yet its last read still showed them free. Nothing
     * was written; trying again shortly may succeed (SEAT_CONTENDED).
     */
    CONTENDED,
    /** Holding these seats would take the user over the event's per-user limit (LIMIT_EXCEEDED, D-005). */
    LIMIT_EXCEEDED
}
