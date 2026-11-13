package com.stampede.inventory.strategy;

import com.stampede.inventory.seat.EventSeat;
import com.stampede.inventory.seat.SeatStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The SQL every Postgres strategy shares (M2.1): reading a seat row into {@link EventSeat}, writing a
 * seat's new state with a version check, inserting the hold row and the per-user allowance upsert.
 * No method here opens a transaction or takes a lock by itself; the caller's transaction and lock
 * order (D-015) decide what these statements wait for.
 */
@Component
class SeatRows {

    private final JdbcClient jdbc;

    SeatRows(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Writes a seat's new state only if the row is still at the version it was read at. Every
     * transition bumps the version by exactly 1 (D-014), so the version before the transition is
     * {@code version - 1}. Returns false when zero rows matched: somebody else changed the seat first.
     * <p>
     * Under READ COMMITTED, if another transaction is writing this row, the UPDATE waits for it; once
     * it commits, Postgres re-checks the WHERE against the row's new version, so a changed version
     * shows up here as false, never as an error.
     */
    boolean writeSeatIfUnchanged(EventSeat seat) {
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
                .param("oldVersion", seat.version() - 1)
                .update();
        return updated == 1;
    }

    /**
     * Like {@link #writeSeatIfUnchanged}, for callers that hold the seat's row lock: then the row
     * can't have changed since it was read, so zero rows means a bug (some code wrote event_seats
     * without the lock), and throwing rolls the whole transaction back.
     */
    void writeLockedSeat(EventSeat seat) {
        if (!writeSeatIfUnchanged(seat)) {
            throw new IllegalStateException("Seat " + seat.seatId() + " of event " + seat.eventId()
                    + " changed while locked (expected version " + (seat.version() - 1) + ")");
        }
    }

    /**
     * Inserts the ACTIVE hold row and returns its expiry. Postgres computes it: now() is the
     * transaction's start time, the same value created_at defaults to, so every instance agrees on it
     * whatever its own clock says (D-015).
     */
    Instant insertHold(HoldRequest request) {
        return jdbc.sql("""
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
    }

    /**
     * The per-user cap (D-005, D-016 step 4b): adds the request's seats to the user's allowance if
     * that stays within the event's limit. One statement checks and increments: the upsert creates
     * the counter row on the user's first hold, and when two requests race, the second waits for the
     * first to commit and then re-evaluates the WHERE against the committed value. The limit is read
     * in the same statement, so nothing can change it in between. Locks the allowance row, which is
     * last in the lock order (D-015): call it after the seats are locked or written.
     *
     * @return false if the seats don't fit (nothing was written)
     */
    boolean addToAllowance(HoldRequest request) {
        return addToAllowance(request.eventId(), request.userId(), request.seatIds().size());
    }

    /** {@link #addToAllowance(HoldRequest)} for a hold that already exists in Redis (M2.2b). */
    boolean addToAllowance(UUID eventId, UUID userId, int seatCount) {
        int written = jdbc.sql("""
                        INSERT INTO user_event_allowance (event_id, user_id, used)
                        SELECT e.id, :userId, :seats FROM events e
                        WHERE e.id = :eventId AND :seats <= e.per_user_limit
                        ON CONFLICT (event_id, user_id) DO UPDATE
                        SET used = user_event_allowance.used + EXCLUDED.used
                        WHERE user_event_allowance.used + EXCLUDED.used
                              <= (SELECT per_user_limit FROM events WHERE id = EXCLUDED.event_id)
                        """)
                .param("eventId", eventId)
                .param("userId", userId)
                .param("seats", seatCount)
                .update();
        return written == 1;
    }

    /** A seat row as a state-machine object; the constructor rejects a row that breaks the CHECK. */
    static EventSeat toEventSeat(ResultSet rs, int rowNum) throws SQLException {
        return new EventSeat(
                rs.getObject("event_id", UUID.class),
                rs.getObject("seat_id", UUID.class),
                SeatStatus.valueOf(rs.getString("status")),
                rs.getObject("hold_id", UUID.class),
                rs.getObject("order_id", UUID.class),
                rs.getLong("version"));
    }

    static List<UUID> uuids(ResultSet rs, String column) throws SQLException {
        return Arrays.asList((UUID[]) rs.getArray(column).getArray());
    }
}
