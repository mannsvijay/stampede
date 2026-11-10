package com.stampede.inventory.strategy;

import java.util.UUID;

/**
 * How seats are taken under contention (PRD 6.2, D-015). Three implementations, chosen per event by
 * {@code events.strategy}: pessimistic (M1.2), optimistic (M2.1) and Redis-first (M2.2).
 * <p>
 * Contract every implementation must keep:
 * <ul>
 *   <li><b>All-or-nothing.</b> {@link #hold} either holds every requested seat or changes nothing.</li>
 *   <li><b>Transitions through {@code EventSeat}.</b> Whether a seat may move is decided by the seat
 *       state machine (D-004, D-014), never by SQL or Lua alone.</li>
 *   <li><b>Expected outcomes are values.</b> A taken seat is a normal answer under contention, returned
 *       as {@link HoldOutcome.Rejected}; exceptions mean a bug or an infrastructure failure.</li>
 *   <li><b>Per-user cap</b> (D-005): a hold adds its seats to {@code user_event_allowance.used} in the
 *       same atomic step that checks {@code events.per_user_limit}, and is rejected with
 *       LIMIT_EXCEEDED (nothing written) if that would exceed it. Every release gives them back.</li>
 *   <li><b>Lock order</b> (D-015): seats are locked in Postgres {@code seat_id} order; a release locks
 *       the hold row before its seats; the per-user allowance row is locked after the seats.</li>
 * </ul>
 */
public interface InventoryStrategy {

    /** Which strategy this is; matches {@code events.strategy}. */
    StrategyKind kind();

    /**
     * Holds every seat in the request for {@code request.holdId()}, or none of them. On success the
     * {@code holds} row exists with status ACTIVE and every seat is HELD by it.
     */
    HoldOutcome hold(HoldRequest request);

    /**
     * Frees every seat the hold owns, gives them back to the user's allowance and closes the hold
     * (status RELEASED or EXPIRED, by reason). Only an ACTIVE hold can be released.
     */
    ReleaseOutcome release(UUID eventId, UUID holdId, ReleaseReason reason);
}
