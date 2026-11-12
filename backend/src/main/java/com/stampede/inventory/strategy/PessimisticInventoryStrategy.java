package com.stampede.inventory.strategy;

import com.stampede.inventory.seat.EventSeat;
import com.stampede.inventory.seat.IllegalSeatTransitionException;
import com.stampede.inventory.strategy.HoldOutcome.Held;
import com.stampede.inventory.strategy.HoldOutcome.Rejected;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Strategy A (PRD 6.2): {@code SELECT ... FOR UPDATE} on the requested event_seats rows, locked in
 * Postgres {@code seat_id} order, inside one transaction (D-015).
 * <p>
 * The pattern: lock, decide with {@link EventSeat}, and only then write. Whether a seat may move is
 * decided by the state machine, never by SQL (D-004); the locks only guarantee that nothing changes
 * underneath while it decides. Release and sell are shared with the optimistic strategy
 * ({@link HoldClosing}, M2.1).
 */
@Component
class PessimisticInventoryStrategy implements InventoryStrategy {

    private final JdbcClient jdbc;
    private final SeatRows seats;
    private final HoldClosing closing;
    private final HoldMetrics metrics;

    PessimisticInventoryStrategy(JdbcClient jdbc, SeatRows seats, HoldClosing closing, HoldMetrics metrics) {
        this.jdbc = jdbc;
        this.seats = seats;
        this.closing = closing;
        this.metrics = metrics;
    }

    @Override
    public StrategyKind kind() {
        return StrategyKind.PESSIMISTIC;
    }

    /**
     * One transaction: it commits when this method returns (also for a Rejected outcome, which has
     * written nothing) and rolls back if it throws. Row locks are held until then. The metrics are
     * recorded inside it, which only touches in-memory meters.
     */
    @Override
    @Transactional
    public HoldOutcome hold(HoldRequest request) {
        long start = System.nanoTime();
        HoldOutcome outcome = null;
        metrics.attempt(kind());
        try {
            outcome = lockAndHold(request);
            return outcome;
        } finally {
            metrics.finished(kind(), outcome, System.nanoTime() - start);
        }
    }

    private HoldOutcome lockAndHold(HoldRequest request) {
        // Step 2: lock the requested seats. ORDER BY seat_id runs before FOR UPDATE takes the locks,
        // so every transaction locks seats in the same (Postgres) order and two requests for
        // overlapping seats can't wait for each other in a circle (deadlock). If another transaction
        // holds a lock, we wait here; once it commits, Postgres gives us the row's new version.
        List<EventSeat> locked = lockSeatsOfRequest(request.eventId(), request.seatIds());

        // Step 3: an id with no row isn't a seat of this event (unknown, or another event's seat).
        // Checked before SEAT_TAKEN: a request naming a non-existent seat is malformed either way.
        Set<UUID> found = new HashSet<>();
        for (EventSeat seat : locked) {
            found.add(seat.seatId());
        }
        List<UUID> unknown = request.seatIds().stream().filter(id -> !found.contains(id)).toList();
        if (!unknown.isEmpty()) {
            return new Rejected(HoldRejection.UNKNOWN_SEAT, unknown);
        }

        // Step 4: let the state machine decide for EVERY seat before writing ANYTHING. That is what
        // makes the hold all-or-nothing: a rejection returns here with nothing written.
        List<EventSeat> heldSeats = new ArrayList<>();
        List<UUID> taken = new ArrayList<>();
        for (EventSeat seat : locked) {
            try {
                heldSeats.add(seat.hold(request.holdId()));
            } catch (IllegalSeatTransitionException e) {
                taken.add(seat.seatId());
            }
        }
        if (!taken.isEmpty()) {
            return new Rejected(HoldRejection.SEAT_TAKEN, taken, takenAgoMs(request.eventId(), taken));
        }

        // Step 4b: the per-user cap (D-005). After the seat decision, so a cap rejection also writes
        // nothing; after the seat locks, because the allowance row is last in the lock order (D-015).
        if (!seats.addToAllowance(request)) {
            return new Rejected(HoldRejection.LIMIT_EXCEEDED, request.seatIds());
        }

        // Step 5: the hold row first, because event_seats.hold_id has a foreign key to holds.
        Instant expiresAt = seats.insertHold(request);

        // Step 6: write the new state of each seat. We hold every lock, so a changed version is a bug.
        for (EventSeat seat : heldSeats) {
            seats.writeLockedSeat(seat);
        }

        // Step 7
        return new Held(request.holdId(), request.seatIds(), expiresAt);
    }

    @Override
    public ReleaseOutcome release(UUID eventId, UUID holdId, ReleaseReason reason) {
        return closing.release(eventId, holdId, reason);
    }

    @Override
    public SellOutcome sell(UUID eventId, UUID holdId, UUID orderId) {
        return closing.sell(eventId, holdId, orderId);
    }

    /** Locks the event's rows for these seat ids, in Postgres seat_id order (see step 2). */
    private List<EventSeat> lockSeatsOfRequest(UUID eventId, List<UUID> seatIds) {
        // IN (:seatIds) with a List: JdbcClient expands it to one placeholder per id.
        return jdbc.sql("""
                        SELECT event_id, seat_id, status, hold_id, order_id, version
                        FROM event_seats
                        WHERE event_id = :eventId AND seat_id IN (:seatIds)
                        ORDER BY seat_id
                        FOR UPDATE
                        """)
                .param("eventId", eventId)
                .param("seatIds", seatIds)
                .query(SeatRows::toEventSeat)
                .list();
    }

    /**
     * SEAT_TAKEN only: how long ago the most recently taken of these seats was taken (D-016). The
     * seats are still locked by this transaction, so their owner pointers can't change. A HELD seat's
     * owner is its hold, a SOLD seat's its order (D-014); both rows are only read, not locked, so the
     * lock order (D-015) is untouched. clock_timestamp(), not now(): the owner may have committed
     * after this transaction started (we waited for its lock), and now() would then be earlier than
     * its created_at. Never negative.
     */
    private long takenAgoMs(UUID eventId, List<UUID> seatIds) {
        return jdbc.sql("""
                        SELECT greatest(0, floor(extract(epoch FROM
                                   clock_timestamp() - max(coalesce(h.created_at, o.created_at))) * 1000))::bigint
                        FROM event_seats es
                        LEFT JOIN holds h ON h.id = es.hold_id
                        LEFT JOIN orders o ON o.id = es.order_id
                        WHERE es.event_id = :eventId AND es.seat_id IN (:seatIds)
                        """)
                .param("eventId", eventId)
                .param("seatIds", seatIds)
                .query(Long.class)
                .single();
    }
}
