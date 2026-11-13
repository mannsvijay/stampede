package com.stampede.inventory.strategy;

import com.stampede.inventory.seat.EventSeat;
import com.stampede.inventory.strategy.ReleaseOutcome.NotActive;
import com.stampede.inventory.strategy.ReleaseOutcome.NotExpired;
import com.stampede.inventory.strategy.ReleaseOutcome.NotFound;
import com.stampede.inventory.strategy.ReleaseOutcome.Released;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Release and sell for the Postgres strategies (pessimistic and optimistic, M2.1). Both start from
 * one existing hold and lock its row first. Only a path holding that lock can change the seats the
 * hold owns (a new hold only ever takes AVAILABLE seats), so once the hold row is locked its seats
 * can't be raced, and an optimistic variant would add code without removing any waiting. Moved here
 * unchanged from the pessimistic strategy (M1.2, M1.4, M1.5).
 * <p>
 * Lock order (D-015): the hold row, then its seats in Postgres {@code seat_id} order, then the user's
 * allowance row. Each method is one transaction (REQUIRED, see D-015 on callers).
 */
@Component
class HoldClosing {

    private final JdbcClient jdbc;
    private final SeatRows seats;

    HoldClosing(JdbcClient jdbc, SeatRows seats) {
        this.jdbc = jdbc;
        this.seats = seats;
    }

    @Transactional
    ReleaseOutcome release(UUID eventId, UUID holdId, ReleaseReason reason) {
        return close(eventId, holdId, reason, true);
    }

    /**
     * Like {@link #release}, for a hold whose end Redis has already decided (Redis-first strategy,
     * D-022): Redis no longer has the hold, so it is over whatever the database clock says. Expiry of a
     * REDIS_FIRST hold is decided by Redis' clock (D-015), and the two clocks can differ by a few ms,
     * so the "not expired yet by now()" re-check of {@link #release} would leave a hold ACTIVE in
     * Postgres that Redis has already given away. Same lock order and same writes otherwise.
     */
    @Transactional
    ReleaseOutcome closeAsRedisDecided(UUID eventId, UUID holdId, ReleaseReason reason) {
        return close(eventId, holdId, reason, false);
    }

    private ReleaseOutcome close(UUID eventId, UUID holdId, ReleaseReason reason, boolean databaseClockDecides) {
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
        if (databaseClockDecides && reason == ReleaseReason.EXPIRED && !hold.get().expired()) {
            return new NotExpired(hold.get().expiresAt());
        }

        // Step 9: then its seats, in the same ORDER BY seat_id FOR UPDATE order as every hold path.
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
            seats.writeLockedSeat(available);
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
    @Transactional
    SellOutcome sell(UUID eventId, UUID holdId, UUID orderId) {
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
            seats.writeLockedSeat(seat);
        }

        // Step S4: close the hold. CONVERTED never reaches release(), so the allowance stays counted.
        // The status in the WHERE is a safety net like writeLockedSeat's version check: we hold the row
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
                        SeatRows.uuids(rs, "seat_ids"), rs.getObject("expires_at", OffsetDateTime.class).toInstant(),
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
                .query(SeatRows::toEventSeat)
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

    private record HoldRow(UUID userId, String status, List<UUID> seatIds, Instant expiresAt, boolean expired) {
    }
}
