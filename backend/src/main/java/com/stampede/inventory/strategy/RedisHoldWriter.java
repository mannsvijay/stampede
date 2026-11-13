package com.stampede.inventory.strategy;

import com.stampede.inventory.seat.EventSeat;
import com.stampede.inventory.seat.IllegalSeatTransitionException;
import com.stampede.inventory.seat.SeatStatus;
import com.stampede.inventory.strategy.RedisHoldStore.SoldSeat;
import com.stampede.inventory.strategy.StreamEntry.HoldFacts;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Writes what Redis decided into Postgres, for the Redis-first strategy (D-022, M2.2b). Used by the
 * stream consumer, the reconciler and (M2.2c) the strategy's expiry.
 * <p>
 * <b>Every method is idempotent</b>: entries are delivered at least once and may arrive out of order
 * (a reclaimed HELD after its RELEASED), and the consumer and the reconciler may do the same work.
 * The {@code holds} row is the gate:
 * <ol>
 *   <li>A HELD inserts the row with {@code ON CONFLICT (id) DO NOTHING}. If a row with that id exists,
 *       in any status, the HELD was already handled and changes nothing.</li>
 *   <li>A RELEASED for a hold Postgres doesn't have yet inserts the row as already over (a
 *       "tombstone": status RELEASED, no seat points at it), so a HELD redelivered later finds it.</li>
 *   <li>A HELD inserts an ACTIVE row only if Redis still has the hold at that moment; otherwise it
 *       writes the tombstone too (EXPIRED or RELEASED, by Redis' clock).</li>
 *   <li>A HELD and a RELEASED of the same hold applied at the same moment meet on the primary key:
 *       the second insert waits for the first transaction and then sees its row.</li>
 * </ol>
 * So a hold that ended in Redis can never become ACTIVE in Postgres again.
 * <p>
 * <b>Lock order (D-015)</b>: an ACTIVE insert takes (1) the new hold row, by inserting it, (2) the
 * seats, {@code ORDER BY seat_id FOR UPDATE}, (3) the allowance row. Closing another hold that blocks
 * it would lock that hold's row after seats, so that happens in its own transaction, before a retry.
 */
@Component
class RedisHoldWriter {

    private static final Logger log = LoggerFactory.getLogger(RedisHoldWriter.class);

    private final JdbcClient jdbc;
    private final SeatRows seats;
    private final HoldClosing closing;
    private final RedisHoldStore store;
    private final RedisStreamMetrics metrics;
    private final TransactionTemplate transaction;

    RedisHoldWriter(JdbcClient jdbc, SeatRows seats, HoldClosing closing, RedisHoldStore store,
                    RedisStreamMetrics metrics, PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.seats = seats;
        this.closing = closing;
        this.store = store;
        this.metrics = metrics;
        this.transaction = new TransactionTemplate(transactions);
    }

    /** What applying an entry did. */
    enum Applied {
        /** Postgres changed as the entry says. */
        APPLIED,
        /** Already applied (a redelivery or a race with the reconciler); nothing changed. */
        DUPLICATE,
        /** The hold was already over in Redis; its row was written as over (a tombstone). */
        STALE,
        /** Postgres disagreed (a seat sold or held there, or the cap) and won; Redis was repaired. */
        CONFLICT
    }

    /** Applies one stream entry. Throws on infrastructure failures; the consumer retries those. */
    Applied apply(StreamEntry entry) {
        Applied result = switch (entry.type()) {
            case HELD -> applyHeld(entry.hold());
            case RELEASED -> applyReleased(entry.hold(), entry.reason());
        };
        metrics.applied(entry.type(), result);
        return result;
    }

    /**
     * Persists a hold Redis granted. Also the reconciler's "roll forward" (rule R1) for a live Redis
     * hold that Postgres doesn't have.
     */
    Applied applyHeld(HoldFacts hold) {
        // Rule 3 above: only a hold Redis still has may become ACTIVE.
        if (store.liveHold(hold.eventId(), hold.holdId()).isEmpty()) {
            return insertRow(hold, statusOfGoneHold(hold.expiresAt())) ? Applied.STALE : Applied.DUPLICATE;
        }
        Attempt attempt = insertActive(hold);
        if (attempt instanceof Blocked blocked) {
            // Usually an older hold that is over in Redis (expired by TTL, so no entry ever came) but
            // not yet closed in Postgres: the sweeper runs every 5 s. Close those, each in its own
            // transaction (lock order), and try once more.
            closeHoldsRedisNoLongerHas(hold, blocked);
            attempt = insertActive(hold);
        }
        return switch (attempt) {
            case Inserted inserted -> Applied.APPLIED;
            case AlreadyThere there -> Applied.DUPLICATE;
            case Blocked blocked -> postgresWins(hold, blocked);
        };
    }

    /** Applies a release Redis made (by the buyer, or a failed checkout). */
    Applied applyReleased(HoldFacts hold, ReleaseReason reason) {
        // Two turns at most: if a HELD of this hold is inserted between our close (NotFound) and our
        // tombstone insert (conflict), the second turn closes that ACTIVE row.
        for (int turn = 0; turn < 2; turn++) {
            ReleaseOutcome outcome = closing.closeAsRedisDecided(hold.eventId(), hold.holdId(), reason);
            switch (outcome) {
                case ReleaseOutcome.Released released -> {
                    return Applied.APPLIED;
                }
                case ReleaseOutcome.NotActive notActive -> {
                    return Applied.DUPLICATE;
                }
                case ReleaseOutcome.NotFound notFound -> {
                    // Rule 2 above: Postgres hasn't seen the HELD yet. Record that the hold is over.
                    if (insertRow(hold, reason.holdStatus())) {
                        return Applied.STALE;
                    }
                }
                case ReleaseOutcome.NotExpired notExpired ->
                        throw new IllegalStateException("A " + reason + " close returned NotExpired");
            }
        }
        throw new IllegalStateException("Hold " + hold.holdId() + " kept changing while applying its release");
    }

    /**
     * Closes a Postgres hold that Redis no longer has: EXPIRED if its time has run out by Redis' clock,
     * otherwise RELEASED (it was released in Redis and that entry isn't applied yet). The caller has
     * checked that Redis doesn't have it. Leaves it alone (NotExpired) if the event isn't loaded in
     * Redis: then "Redis doesn't have it" means nothing, and the loader will restore it.
     */
    ReleaseOutcome closeGone(UUID eventId, UUID holdId) {
        Optional<Instant> expiresAt = expiresAt(eventId, holdId);
        if (expiresAt.isEmpty()) {
            return new ReleaseOutcome.NotFound();
        }
        if (!store.isLoaded(eventId)) {
            return new ReleaseOutcome.NotExpired(expiresAt.get());
        }
        ReleaseReason reason = expiresAt.get().toEpochMilli() <= store.nowMillis()
                ? ReleaseReason.EXPIRED : ReleaseReason.RELEASED;
        return closing.closeAsRedisDecided(eventId, holdId, reason);
    }

    /**
     * Expiry of a REDIS_FIRST hold, for the existing sweeper (through the M2.2c strategy's
     * {@code release(…, EXPIRED)}). Redis' clock decides: a hold Redis still has is not expired.
     */
    ReleaseOutcome expire(UUID eventId, UUID holdId) {
        return switch (store.release(eventId, holdId, ReleaseReason.EXPIRED)) {
            case RedisHoldStore.Release.NotExpired notExpired -> new ReleaseOutcome.NotExpired(notExpired.expiresAt());
            case RedisHoldStore.Release.Gone gone -> closeGone(eventId, holdId);
            case RedisHoldStore.Release.Released released ->
                    throw new IllegalStateException("release.lua released a hold for reason EXPIRED");
        };
    }

    // ---- the ACTIVE insert ----

    private sealed interface Attempt {
    }

    private record Inserted() implements Attempt {
    }

    private record AlreadyThere() implements Attempt {
    }

    /** What stopped the insert; everything was rolled back. */
    private record Blocked(Set<UUID> heldBy, List<UUID> soldSeats, List<UUID> unknownSeats, boolean overCap)
            implements Attempt {
    }

    private Attempt insertActive(HoldFacts hold) {
        return transaction.execute(status -> {
            // (1) The hold row first: the primary key decides whether this hold was already handled.
            if (!insertRow(hold, "ACTIVE")) {
                warnIfReused(hold);
                return new AlreadyThere();
            }
            // (2) Its seats, in Postgres seat_id order like every other path (D-015).
            List<EventSeat> locked = jdbc.sql("""
                            SELECT event_id, seat_id, status, hold_id, order_id, version
                            FROM event_seats
                            WHERE event_id = :eventId AND seat_id IN (:seatIds)
                            ORDER BY seat_id
                            FOR UPDATE
                            """)
                    .param("eventId", hold.eventId())
                    .param("seatIds", hold.seatIds())
                    .query(SeatRows::toEventSeat)
                    .list();
            Set<UUID> found = new HashSet<>();
            List<EventSeat> heldSeats = new ArrayList<>();
            Set<UUID> heldBy = new LinkedHashSet<>();
            List<UUID> sold = new ArrayList<>();
            for (EventSeat seat : locked) {
                found.add(seat.seatId());
                try {
                    // The state machine still guards every Postgres seat change (D-022).
                    heldSeats.add(seat.hold(hold.holdId()));
                } catch (IllegalSeatTransitionException e) {
                    if (seat.status() == SeatStatus.HELD) {
                        heldBy.add(seat.holdId());
                    } else {
                        sold.add(seat.seatId());
                    }
                }
            }
            List<UUID> unknown = hold.seatIds().stream().filter(id -> !found.contains(id)).toList();
            if (!heldBy.isEmpty() || !sold.isEmpty() || !unknown.isEmpty()) {
                status.setRollbackOnly();
                return new Blocked(heldBy, sold, unknown, false);
            }
            // (3) The cap (D-005), last in the lock order. Redis checked it already; Postgres can
            // disagree when it still counts a hold that is over in Redis but not yet closed here.
            if (!seats.addToAllowance(hold.eventId(), hold.userId(), hold.seatIds().size())) {
                status.setRollbackOnly();
                return new Blocked(Set.of(), List.of(), List.of(), true);
            }
            for (EventSeat seat : heldSeats) {
                seats.writeLockedSeat(seat);
            }
            return new Inserted();
        });
    }

    /** Closes the holds blocking an insert that Redis no longer has, each in its own transaction. */
    private void closeHoldsRedisNoLongerHas(HoldFacts hold, Blocked blocked) {
        Set<UUID> candidates = new LinkedHashSet<>(blocked.heldBy());
        if (blocked.overCap()) {
            candidates.addAll(jdbc.sql("""
                            SELECT id FROM holds
                            WHERE event_id = :eventId AND user_id = :userId AND status = 'ACTIVE' AND id <> :holdId
                            """)
                    .param("eventId", hold.eventId())
                    .param("userId", hold.userId())
                    .param("holdId", hold.holdId())
                    .query(UUID.class)
                    .list());
        }
        for (UUID other : candidates) {
            if (store.liveHold(hold.eventId(), other).isEmpty()) {
                closeGone(hold.eventId(), other);
            }
        }
    }

    /**
     * Postgres disagrees with a live Redis hold and wins (D-022): a seat is sold or held by a hold Redis
     * still has, a seat isn't in the event, or the cap is full. The hold is recorded as RELEASED, sold
     * seats get their marker back in Redis, and the hold is released in Redis. The marker goes first:
     * release.lua deletes only seat keys that still name this hold, so the sold seats stay taken.
     */
    private Applied postgresWins(HoldFacts hold, Blocked blocked) {
        log.warn("Redis hold {} of event {} conflicts with Postgres (held by {}, sold {}, unknown {}, over cap {}); "
                        + "Postgres wins and the hold is released in Redis", hold.holdId(), hold.eventId(),
                blocked.heldBy(), blocked.soldSeats(), blocked.unknownSeats(), blocked.overCap());
        insertRow(hold, "RELEASED");
        if (!blocked.soldSeats().isEmpty()) {
            for (SoldSeat sold : soldSeats(hold.eventId(), blocked.soldSeats())) {
                store.markSold(hold.eventId(), sold);
            }
        }
        store.release(hold.eventId(), hold.holdId(), ReleaseReason.RELEASED);
        return Applied.CONFLICT;
    }

    // ---- SQL ----

    /** Inserts the hold row with Redis' times. Returns false if a row with this id already exists. */
    private boolean insertRow(HoldFacts hold, String status) {
        int inserted = jdbc.sql("""
                        INSERT INTO holds (id, event_id, user_id, seat_ids, created_at, expires_at, status)
                        VALUES (:holdId, :eventId, :userId, :seatIds, :createdAt, :expiresAt, :status)
                        ON CONFLICT (id) DO NOTHING
                        """)
                .param("holdId", hold.holdId())
                .param("eventId", hold.eventId())
                .param("userId", hold.userId())
                .param("seatIds", hold.seatIds().toArray(UUID[]::new))
                .param("createdAt", hold.createdAt().atOffset(ZoneOffset.UTC))
                .param("expiresAt", hold.expiresAt().atOffset(ZoneOffset.UTC))
                .param("status", status)
                .update();
        return inserted == 1;
    }

    /**
     * A hold id is new for every attempt (D-015). If the row that already exists is a different hold
     * (another user or other seats), a caller reused an id after its hold ended: Redis can't catch
     * that (D-022), and the entry is dropped here, so at least say so loudly.
     */
    private void warnIfReused(HoldFacts hold) {
        jdbc.sql("SELECT event_id, user_id, seat_ids FROM holds WHERE id = :holdId")
                .param("holdId", hold.holdId())
                .query((rs, n) -> rs.getObject("event_id", UUID.class).equals(hold.eventId())
                        && rs.getObject("user_id", UUID.class).equals(hold.userId())
                        && new HashSet<>(SeatRows.uuids(rs, "seat_ids")).equals(new HashSet<>(hold.seatIds())))
                .optional()
                .filter(same -> !same)
                .ifPresent(same -> log.error("Hold id {} was reused for a different hold; the stream entry for "
                        + "user {} seats {} was dropped (D-015)", hold.holdId(), hold.userId(), hold.seatIds()));
    }

    private Optional<Instant> expiresAt(UUID eventId, UUID holdId) {
        return jdbc.sql("SELECT expires_at FROM holds WHERE id = :holdId AND event_id = :eventId")
                .param("holdId", holdId)
                .param("eventId", eventId)
                .query((rs, n) -> rs.getObject("expires_at", OffsetDateTime.class).toInstant())
                .optional();
    }

    private List<SoldSeat> soldSeats(UUID eventId, List<UUID> seatIds) {
        return jdbc.sql("""
                        SELECT es.seat_id, es.order_id, o.created_at
                        FROM event_seats es JOIN orders o ON o.id = es.order_id
                        WHERE es.event_id = :eventId AND es.seat_id IN (:seatIds) AND es.status = 'SOLD'
                        """)
                .param("eventId", eventId)
                .param("seatIds", seatIds)
                .query((rs, n) -> new SoldSeat(rs.getObject("seat_id", UUID.class),
                        rs.getObject("order_id", UUID.class),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant()))
                .list();
    }

    /** EXPIRED if the hold's time has run out by Redis' clock, otherwise RELEASED. */
    private String statusOfGoneHold(Instant expiresAt) {
        return expiresAt.toEpochMilli() <= store.nowMillis() ? "EXPIRED" : "RELEASED";
    }
}
