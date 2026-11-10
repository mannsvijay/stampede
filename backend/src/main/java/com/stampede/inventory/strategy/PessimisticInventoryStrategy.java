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
            return new Rejected(HoldRejection.SEAT_TAKEN, taken, takenAgoMs(request.eventId(), taken));
        }

        // Step 4b: the per-user cap (D-005). After the seat decision, so a cap rejection also writes
        // nothing; after the seat locks, because the allowance row is last in the lock order (D-015).
        // One statement checks and increments: the upsert creates the user's counter row on their
        // first hold, and when two requests race, the second waits for the first to commit and then
        // re-evaluates the WHERE against the committed value. Zero rows written = over the limit.
        // The limit is read here, in the same statement, so nothing can change it in between.
        int allowance = jdbc.sql("""
                        INSERT INTO user_event_allowance (event_id, user_id, used)
                        SELECT e.id, :userId, :seats FROM events e
                        WHERE e.id = :eventId AND :seats <= e.per_user_limit
                        ON CONFLICT (event_id, user_id) DO UPDATE
                        SET used = user_event_allowance.used + EXCLUDED.used
                        WHERE user_event_allowance.used + EXCLUDED.used
                              <= (SELECT per_user_limit FROM events WHERE id = EXCLUDED.event_id)
                        """)
                .param("eventId", request.eventId())
                .param("userId", request.userId())
                .param("seats", request.seatIds().size())
                .update();
        if (allowance == 0) {
            return new Rejected(HoldRejection.LIMIT_EXCEEDED, request.seatIds());
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
        Optional<HoldRow> hold = lockHold(eventId, holdId);
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
        List<EventSeat> locked = lockSeatsOfHold(eventId, holdId, hold.get().seatIds());

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

        // Step 11b: give the seats back to the user's allowance (D-005), last in the lock order
        // (hold, seats, allowance; D-015). Every reason that reaches here frees the seats, so every
        // one decrements; a CONVERTED hold never gets here, so bought seats keep counting. If the
        // counter is missing or would go below zero (ck_user_event_allowance_used_nonnegative), it
        // has drifted from the holds: that is a bug, and the exception rolls the release back rather
        // than hiding it (the hold stays ACTIVE and the sweeper keeps reporting it).
        int allowance = jdbc.sql("""
                        UPDATE user_event_allowance SET used = used - :seats
                        WHERE event_id = :eventId AND user_id = :userId
                        """)
                .param("seats", freed.size())
                .param("eventId", eventId)
                .param("userId", hold.get().userId())
                .update();
        if (allowance != 1) {
            throw new IllegalStateException("No allowance row for user " + hold.get().userId() + " in event "
                    + eventId + " while releasing hold " + holdId);
        }

        // Step 12: close the hold. The status depends on why it ended.
        jdbc.sql("UPDATE holds SET status = :status WHERE id = :holdId")
                .param("status", reason.holdStatus())
                .param("holdId", holdId)
                .update();

        // Step 13
        return new Released(freed);
    }

    /**
     * Same lock order as release (D-015): the hold row, then its seats. The allowance row is not
     * touched at all, because sold seats keep counting against the user's cap (D-016).
     */
    @Override
    @Transactional
    public SellOutcome sell(UUID eventId, UUID holdId, UUID orderId) {
        // Step S1: lock the hold row first. A sell, a cancel and the expiry sweeper for the same hold
        // take turns here; whoever comes second sees the status the first one wrote.
        Optional<HoldRow> hold = lockHold(eventId, holdId);
        if (hold.isEmpty()) {
            return new SellOutcome.NotFound();
        }
        if (!hold.get().status().equals("ACTIVE")) {
            return new SellOutcome.NotActive(hold.get().status());
        }
        // An ACTIVE hold can be past its expiry: the sweeper runs every few seconds, so there is a
        // window where the status hasn't caught up. Checking status alone would sell an expired hold.
        // The database clock decides, as for every hold expiry (D-015).
        if (hold.get().expired()) {
            return new SellOutcome.HoldExpired(hold.get().expiresAt());
        }

        // Step S2: its seats, in seat_id order.
        List<EventSeat> locked = lockSeatsOfHold(eventId, holdId, hold.get().seatIds());

        // Step S3: the state machine moves every seat HELD -> SOLD before anything is written. It
        // checks the seat is HELD by this hold; we hold all the locks and the ownership check above
        // passed, so a rejection is corrupt data and rolls everything back.
        List<EventSeat> soldSeats = new ArrayList<>();
        for (EventSeat seat : locked) {
            soldSeats.add(seat.sell(holdId, orderId));
        }
        for (EventSeat seat : soldSeats) {
            writeSeat(seat);
        }

        // Step S4: close the hold. CONVERTED never reaches release(), so the allowance stays counted.
        // The status in the WHERE is a safety net like writeSeat's version check: we hold the row
        // lock and saw ACTIVE, so zero rows means something wrote the hold without the lock (a bug).
        int converted = jdbc.sql("UPDATE holds SET status = 'CONVERTED' WHERE id = :holdId AND status = 'ACTIVE'")
                .param("holdId", holdId)
                .update();
        if (converted != 1) {
            throw new IllegalStateException("Hold " + holdId + " changed while locked during sell");
        }

        return new SellOutcome.Sold(soldSeats.stream().map(EventSeat::seatId).toList());
    }

    /** Locks the hold row (step 8 / S1), the first lock of every path that starts from a hold. */
    private Optional<HoldRow> lockHold(UUID eventId, UUID holdId) {
        return jdbc.sql("""
                        SELECT user_id, status, seat_ids, expires_at, expires_at <= now() AS expired
                        FROM holds
                        WHERE id = :holdId AND event_id = :eventId
                        FOR UPDATE
                        """)
                .param("holdId", holdId)
                .param("eventId", eventId)
                .query((rs, rowNum) -> new HoldRow(rs.getObject("user_id", UUID.class), rs.getString("status"),
                        uuids(rs, "seat_ids"), rs.getObject("expires_at", OffsetDateTime.class).toInstant(),
                        rs.getBoolean("expired")))
                .optional();
    }

    /**
     * Locks the seats an ACTIVE hold owns (step 9 / S2), in seat_id order. event_seats.hold_id is the
     * authoritative owner; holds.seat_ids is a convenience copy. If the two disagree, the data is
     * already wrong (invariant broken): throw and roll back instead of acting on half a hold.
     */
    private List<EventSeat> lockSeatsOfHold(UUID eventId, UUID holdId, List<UUID> listedSeats) {
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
        Set<UUID> owned = new HashSet<>();
        for (EventSeat seat : locked) {
            owned.add(seat.seatId());
        }
        if (!owned.equals(new HashSet<>(listedSeats))) {
            throw new IllegalStateException("Active hold " + holdId + " lists seats " + listedSeats
                    + " but owns " + owned);
        }
        return locked;
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

    private static List<UUID> uuids(ResultSet rs, String column) throws SQLException {
        return Arrays.asList((UUID[]) rs.getArray(column).getArray());
    }

    private record HoldRow(UUID userId, String status, List<UUID> seatIds, Instant expiresAt, boolean expired) {
    }
}
