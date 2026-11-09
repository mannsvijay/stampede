package com.stampede.inventory.strategy;

import com.stampede.inventory.seat.EventSeat;
import com.stampede.inventory.seat.IllegalSeatTransitionException;
import com.stampede.inventory.seat.SeatStatus;
import com.stampede.inventory.strategy.HoldOutcome.Held;
import com.stampede.inventory.strategy.HoldOutcome.Rejected;
import com.stampede.inventory.strategy.ReleaseOutcome.NotActive;
import com.stampede.inventory.strategy.ReleaseOutcome.NotExpired;
import com.stampede.inventory.strategy.ReleaseOutcome.NotFound;
import com.stampede.inventory.strategy.ReleaseOutcome.Released;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Strategy A (PRD 6.2): {@code SELECT ... FOR UPDATE} on the requested event_seats rows, locked in
 * Postgres {@code seat_id} order, inside one transaction (D-015).
 * <p>
 * The pattern in both methods: lock, decide with {@link EventSeat}, and only then write. Whether a
 * seat may move is decided by the state machine, never by SQL (D-004); the locks only guarantee that
 * nothing changes underneath while it decides.
 */
@Component
class PessimisticInventoryStrategy implements InventoryStrategy {

    private final JdbcClient jdbc;

    PessimisticInventoryStrategy(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public StrategyKind kind() {
        return StrategyKind.PESSIMISTIC;
    }

    /**
     * One transaction: it commits when this method returns (also for a Rejected outcome, which has
     * written nothing) and rolls back if it throws. Row locks are held until then.
     */
    @Override
    @Transactional
    public HoldOutcome hold(HoldRequest request) {
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
            return new Rejected(HoldRejection.SEAT_TAKEN, taken);
        }

        // Step 5: the hold row first, because event_seats.hold_id has a foreign key to holds.
        // The expiry is computed by Postgres: now() is the transaction's start time, the same value
        // created_at defaults to, so every instance agrees on it whatever its own clock says (D-015).
        Instant expiresAt = jdbc.sql("""
                        INSERT INTO holds (id, event_id, user_id, seat_ids, expires_at, status)
                        VALUES (:holdId, :eventId, :userId, :seatIds,
                                now() + :holdForMillis * interval '1 millisecond', 'ACTIVE')
                        RETURNING expires_at
                        """)
                .param("holdId", request.holdId())
                .param("eventId", request.eventId())
                .param("userId", request.userId())
                .param("seatIds", request.seatIds().toArray(UUID[]::new))
                .param("holdForMillis", request.holdFor().toMillis())
                .query((rs, rowNum) -> rs.getObject("expires_at", OffsetDateTime.class).toInstant())
                .single();

        // Step 6: write the new state of each seat.
        for (EventSeat seat : heldSeats) {
            writeSeat(seat);
        }

        // Step 7
        return new Held(request.holdId(), request.seatIds(), expiresAt);
    }

    @Override
    @Transactional
    public ReleaseOutcome release(UUID eventId, UUID holdId, ReleaseReason reason) {
        // Step 8: lock the hold row FIRST (lock order, D-015). A buyer's cancel and the expiry
        // sweeper for the same hold now take turns: the second one waits here, then sees the status
        // the first one wrote and returns NotActive.
        Optional<HoldRow> hold = jdbc.sql("""
                        SELECT status, seat_ids, expires_at, expires_at <= now() AS expired
                        FROM holds
                        WHERE id = :holdId AND event_id = :eventId
                        FOR UPDATE
                        """)
                .param("holdId", holdId)
                .param("eventId", eventId)
                .query((rs, rowNum) -> new HoldRow(rs.getString("status"), uuids(rs, "seat_ids"),
                        rs.getObject("expires_at", OffsetDateTime.class).toInstant(), rs.getBoolean("expired")))
                .optional();
        if (hold.isEmpty()) {
            return new NotFound();
        }
        if (!hold.get().status().equals("ACTIVE")) {
            return new NotActive(hold.get().status());
        }
        // The sweeper decided "expired" before it had the lock. Check again now that we hold it: the
        // hold may have been extended in between (PRD 6.3), and then it must not be expired. The
        // comparison runs in SQL so the database clock decides, like everywhere else (D-015).
        if (reason == ReleaseReason.EXPIRED && !hold.get().expired()) {
            return new NotExpired(hold.get().expiresAt());
        }

        // Step 9: then its seats, in the same ORDER BY seat_id FOR UPDATE order as hold().
        // event_seats.hold_id is the authoritative owner; holds.seat_ids is a convenience copy.
        List<EventSeat> locked = jdbc.sql("""
                        SELECT event_id, seat_id, status, hold_id, order_id, version
                        FROM event_seats
                        WHERE event_id = :eventId AND hold_id = :holdId
                        ORDER BY seat_id
                        FOR UPDATE
                        """)
                .param("eventId", eventId)
                .param("holdId", holdId)
                .query(this::toEventSeat)
                .list();
        // An ACTIVE hold must own exactly the seats it lists. If not, the data is already wrong
        // (invariant broken); stop and roll back instead of releasing half a hold.
        Set<UUID> owned = new HashSet<>();
        for (EventSeat seat : locked) {
            owned.add(seat.seatId());
        }
        if (!owned.equals(new HashSet<>(hold.get().seatIds()))) {
            throw new IllegalStateException("Active hold " + holdId + " lists seats " + hold.get().seatIds()
                    + " but owns " + owned);
        }

        // Step 10: the state machine moves each seat. A rejection here would mean corrupt data, so
        // the exception is allowed to propagate and roll everything back.
        List<UUID> freed = new ArrayList<>();
        for (EventSeat seat : locked) {
            EventSeat available = switch (reason) {
                case RELEASED -> seat.release(holdId);
                case EXPIRED -> seat.expire(holdId);
                case SAGA_FAILED -> seat.failSaga(holdId);
            };
            // Step 11
            writeSeat(available);
            freed.add(available.seatId());
        }

        // Step 12: close the hold. The status depends on why it ended.
        jdbc.sql("UPDATE holds SET status = :status WHERE id = :holdId")
                .param("status", reason.holdStatus())
                .param("holdId", holdId)
                .update();

        // Step 13
        return new Released(freed);
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
                .query(this::toEventSeat)
                .list();
    }

    /**
     * Writes a seat's new state (step 6 and 11). The version check is a safety net: we hold the row
     * lock, so the row can't have changed since we read it. Zero rows updated therefore means a bug
     * (some code wrote event_seats without the lock), and throwing rolls the whole transaction back.
     */
    private void writeSeat(EventSeat seat) {
        int updated = jdbc.sql("""
                        UPDATE event_seats
                        SET status = :status, hold_id = :holdId, order_id = :orderId, version = :newVersion
                        WHERE event_id = :eventId AND seat_id = :seatId AND version = :oldVersion
                        """)
                .param("status", seat.status().name())
                .param("holdId", seat.holdId())
                .param("orderId", seat.orderId())
                .param("newVersion", seat.version())
                .param("eventId", seat.eventId())
                .param("seatId", seat.seatId())
                .param("oldVersion", seat.version() - 1)  // every transition bumps the version by 1
                .update();
        if (updated != 1) {
            throw new IllegalStateException("Seat " + seat.seatId() + " of event " + seat.eventId()
                    + " changed while locked (expected version " + (seat.version() - 1) + ")");
        }
    }

    /** A locked row as a state-machine object; the constructor rejects a row that breaks the CHECK. */
    private EventSeat toEventSeat(ResultSet rs, int rowNum) throws SQLException {
        return new EventSeat(
                rs.getObject("event_id", UUID.class),
                rs.getObject("seat_id", UUID.class),
                SeatStatus.valueOf(rs.getString("status")),
                rs.getObject("hold_id", UUID.class),
                rs.getObject("order_id", UUID.class),
                rs.getLong("version"));
    }

    private static List<UUID> uuids(ResultSet rs, String column) throws SQLException {
        return Arrays.asList((UUID[]) rs.getArray(column).getArray());
    }

    private record HoldRow(String status, List<UUID> seatIds, Instant expiresAt, boolean expired) {
    }
}
