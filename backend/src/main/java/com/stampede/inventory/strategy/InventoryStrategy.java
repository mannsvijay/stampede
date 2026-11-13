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
 *   <li><b>Lock order</b> (D-015): seats are locked in Postgres {@code seat_id} order; a release or a
 *       sell locks the hold row before its seats; the per-user allowance row is locked after the seats.</li>
 * </ul>
 * Redis-first (M2.2c, D-022) keeps the same contract with one difference in timing: its {@link #hold}
 * decides and answers in Redis (cap included, inside Lua), and the {@code holds} row, the HELD seats and
 * the allowance reach Postgres shortly after, through the stream consumer. Its release and sell lock the
 * hold row first like the others.
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

    /**
     * Sells every seat the hold owns to {@code orderId} (HELD to SOLD) and closes the hold as CONVERTED
     * (M1.5, D-017). Only an ACTIVE hold whose {@code expires_at} is still in the future by the
     * strategy's own clock can be sold; anything else changes nothing. The user's allowance is not
     * touched: bought seats keep counting (D-016). The order row must already be committed, because
     * {@code event_seats.order_id} has a foreign key to it.
     */
    SellOutcome sell(UUID eventId, UUID holdId, UUID orderId);
}
