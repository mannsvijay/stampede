package com.stampede.inventory.strategy;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * A request to hold seats. Validated here, once, so no strategy can forget a check.
 *
 * @param holdId  chosen by the caller (D-002): the Redis-first strategy creates the hold before
 *                Postgres knows about it, so the id must exist first
 * @param seatIds at least one, no duplicates (PLAN note on M1.4: {@code holds.seat_ids} has no
 *                database-level uniqueness), in any order
 * @param holdFor how long the hold lasts. A duration, not an instant: each strategy computes the expiry
 *                from its own authoritative clock (Postgres {@code now()}, or Redis time) (D-015)
 */
public record HoldRequest(UUID holdId, UUID eventId, UUID userId, List<UUID> seatIds, Duration holdFor) {

    public HoldRequest {
        Objects.requireNonNull(holdId, "holdId");
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(seatIds, "seatIds");
        Objects.requireNonNull(holdFor, "holdFor");
        if (seatIds.isEmpty()) {
            throw new IllegalArgumentException("A hold needs at least one seat");
        }
        Set<UUID> seen = new HashSet<>();
        for (UUID seatId : seatIds) {
            Objects.requireNonNull(seatId, "seatIds must not contain null");
            if (!seen.add(seatId)) {
                throw new IllegalArgumentException("Seat " + seatId + " appears more than once in the request");
            }
        }
        if (holdFor.isNegative() || holdFor.isZero()) {
            throw new IllegalArgumentException("holdFor must be positive, was " + holdFor);
        }
        seatIds = List.copyOf(seatIds);
    }
}
