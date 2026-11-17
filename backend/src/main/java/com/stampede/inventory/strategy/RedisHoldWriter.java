package com.stampede.inventory.strategy;

import com.stampede.inventory.seat.EventSeat;
import com.stampede.inventory.seat.IllegalSeatTransitionException;
import com.stampede.inventory.seat.SeatStatus;
import com.stampede.inventory.strategy.RedisHoldStore.SoldMember;
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
 *   <li>A HELD inserts the row with {@code ON CONFLICT DO NOTHING}. If a row with that id exists,
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
    /**
     * Bound on close-and-retry passes of one HELD (review follow-up, 2026-11-15). Each pass after the
     * first must have closed a hold Redis no longer has, so reaching it needs that many holds ending
     * while one HELD is being persisted.
     */
    private static final int MAX_PASSES = 5;

    private final JdbcClient jdbc;
    private final SeatRows seats;
    private final HoldClosing closing;
    private final RedisHoldStore store;
    private final RedisStreamMetrics metrics;
    private final TransactionTemplate transaction;
    /** Test hook only: runs between "Redis doesn't have this hold" and the epoch comparison. */
    private volatile Runnable beforeEpochCheck = () -> { };
    /** Test hook only: runs after an insert was blocked, before the holds that blocked it are closed. */
    private volatile Runnable afterBlocked = () -> { };

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
        CONFLICT,
        /**
         * The entry's event is no longer REDIS_FIRST (M2.3a, D-023): nothing written. A switch away from
         * REDIS_FIRST is refused while the event has unapplied entries, so this means a bug or a
         * hand-edited strategy; it is logged and counted.
         */
        NOT_REDIS_FIRST
    }

    /** Applies one stream entry. Throws on infrastructure failures; the consumer retries those. */
    Applied apply(StreamEntry entry) {
        Applied result;
        if (!isRedisFirst(entry.hold().eventId())) {
            log.warn("Inventory stream entry {} {} of hold {}: event {} is no longer REDIS_FIRST; nothing applied",
                    entry.id(), entry.type(), entry.hold().holdId(), entry.hold().eventId());
            result = Applied.NOT_REDIS_FIRST;
        } else {
            result = switch (entry.type()) {
                case HELD -> applyHeld(entry.hold());
                case RELEASED -> applyReleased(entry.hold(), entry.reason());
            };
        }
        metrics.applied(entry.type(), result);
        return result;
    }

    /** The consumer guard's read (M2.3a): one primary-key lookup per entry, no lock. */
    private boolean isRedisFirst(UUID eventId) {
        return jdbc.sql("SELECT strategy FROM events WHERE id = :eventId")
                .param("eventId", eventId)
                .query(String.class)
                .optional()
                .map(StrategyKind.REDIS_FIRST.name()::equals)
                .orElse(false);
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
        Optional<String> epoch = store.loadEpoch(hold.eventId());
        Attempt attempt = insertActive(hold);
        for (int pass = 1; attempt instanceof Blocked blocked; pass++) {
            afterBlocked.run();
            // Usually an older hold that is over in Redis (expired by TTL, so no entry ever came) but
            // not yet closed in Postgres: the sweeper runs every 5 s. Close those, each in its own
            // transaction (lock order), and try again.
            boolean progress = closeHoldsRedisNoLongerHas(hold, blocked);
            // Review follow-up (2026-11-15): this hold may have ended while we waited for locks (its
            // buyer released it, or its TTL ran out), and a newer hold may have taken its seat and been
            // persisted. That is no disagreement: record the hold as over, as rule 3 does. Only if the
            // load epoch is unchanged: after a wipe and reload, Redis lost the hold because the loader
            // restored Postgres' view, so Postgres did win (D-022).
            if (store.liveHold(hold.eventId(), hold.holdId()).isEmpty()
                    && epoch.equals(store.loadEpoch(hold.eventId()))) {
                return insertRow(hold, statusOfGoneHold(hold.expiresAt())) ? Applied.STALE : Applied.DUPLICATE;
            }
            // The first pass always tries again (as before). After that, only while a pass found a
            // blocking hold gone from Redis (closed by us or by another path): a cap can fill with
            // holds that each ended while the previous pass waited. Postgres wins only after a pass in
            // which every blocking hold was still live in Redis (or after MAX_PASSES).
            if ((pass > 1 && !progress) || pass >= MAX_PASSES) {
                return postgresWins(hold, blocked);
            }
            attempt = insertActive(hold);
        }
        return attempt instanceof Inserted ? Applied.APPLIED : Applied.DUPLICATE;
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
                // closeAsRedisDecided has no strategy re-check; the consumer guard in apply() is the check here.
                case ReleaseOutcome.StrategyChanged changed ->
                        throw new IllegalStateException("A " + reason + " close returned StrategyChanged");
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
        ReleaseOutcome outcome = closing.closeAsRedisDecided(eventId, holdId, reasonForGoneHold(expiresAt.get()));
        if (outcome instanceof ReleaseOutcome.Released) {
            // Redis lost the hold's hash and seats, but its cap member may still count (M2.2c-2, found by
            // invariant 5): remove it, so Redis' cap agrees with the Postgres allowance just decremented.
            jdbc.sql("SELECT user_id, cardinality(seat_ids) AS seats FROM holds WHERE id = ?")
                    .param(holdId)
                    .query((rs, n) -> store.removeGoneHoldMember(eventId, rs.getObject("user_id", UUID.class), holdId,
                            rs.getInt("seats")))
                    .optional();
        }
        return outcome;
    }

    /**
     * Expiry of a REDIS_FIRST hold, for the existing sweeper (through the strategy's
     * {@code release(…, EXPIRED)}). Redis' clock decides: a hold Redis still has is not expired.
     * <p>
     * <b>Lock first</b> (M2.2c): the hold row is locked before release.lua runs, like every Redis-first
     * release and sale. That matters for a pinned hold (pin.lua): its hash has no TTL, so once its time
     * has run out release.lua frees it; under the row lock that can only happen after the sale that
     * pinned it has committed (then the row is CONVERTED and nothing happens) or rolled back.
     */
    ReleaseOutcome expire(UUID eventId, UUID holdId) {
        return closing.closeWith(eventId, holdId, this::expireDecision);
    }

    /**
     * The decision of {@link #expire}, under the hold row lock. Separate so the strategy can put its
     * strategy re-check in front of it (M2.3a) before any Lua runs.
     */
    HoldClosing.CloseDecision expireDecision(HoldClosing.LockedHold hold) {
        UUID eventId = hold.eventId();
        UUID holdId = hold.holdId();
        // Read the load epoch before asking Redis: if Redis is wiped and reloaded between that read
        // and the GONE below, "Redis doesn't have it" means nothing (the reload may have restored it).
        Optional<String> epoch = store.loadEpoch(eventId);
        if (epoch.isEmpty()) {
            // Not loaded: Redis having nothing means nothing. The loader decides (D-022).
            return new HoldClosing.CloseDecision.Keep(new ReleaseOutcome.NotExpired(hold.expiresAt()));
        }
        return switch (store.release(eventId, holdId, ReleaseReason.EXPIRED)) {
            case RedisHoldStore.Release.NotExpired notExpired ->
                    new HoldClosing.CloseDecision.Keep(new ReleaseOutcome.NotExpired(notExpired.expiresAt()));
            // A pinned hold whose sale never finished, past its expiry.
            case RedisHoldStore.Release.Released released ->
                    new HoldClosing.CloseDecision.Close(ReleaseReason.EXPIRED, false);
            case RedisHoldStore.Release.Gone gone -> !epoch.equals(store.loadEpoch(eventId))
                    ? new HoldClosing.CloseDecision.Keep(new ReleaseOutcome.NotExpired(hold.expiresAt()))
                    : new HoldClosing.CloseDecision.Close(reasonForGoneHold(hold.expiresAt()), false);
        };
    }

    /**
     * Why a hold Redis no longer has is over: EXPIRED if its time has run out by Redis' clock, otherwise
     * RELEASED (released in Redis, and that entry isn't applied yet).
     */
    ReleaseReason reasonForGoneHold(Instant expiresAt) {
        return expiresAt.toEpochMilli() <= store.nowMillis() ? ReleaseReason.EXPIRED : ReleaseReason.RELEASED;
    }

    // ---- the ACTIVE insert ----

    private sealed interface Attempt {
    }

    private record Inserted() implements Attempt {
    }

    private record AlreadyThere() implements Attempt {
    }

    /**
     * What stopped the insert; everything was rolled back. {@code capHolds}: when over the cap, the
     * user's other ACTIVE holds, read in the same transaction (so it lists every hold the cap counted).
     */
    private record Blocked(Set<UUID> heldBy, List<UUID> soldSeats, List<UUID> unknownSeats, boolean overCap,
                           List<UUID> capHolds) implements Attempt {
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
                return new Blocked(heldBy, sold, unknown, false, List.of());
            }
            // (3) The cap (D-005), last in the lock order. Redis checked it already; Postgres can
            // disagree when it still counts a hold that is over in Redis but not yet closed here.
            if (!seats.addToAllowance(hold.eventId(), hold.userId(), hold.seatIds().size())) {
                // Review follow-up (round 711): list the holds the cap counted here, not after the
                // rollback. The failed upsert still locks the user's allowance row (ON CONFLICT DO UPDATE
                // locks the row even when its WHERE is false), so a close that would lower it waits for
                // our rollback, and every hold it counted is still ACTIVE in this list.
                List<UUID> capHolds = activeHoldsOfUser(hold);
                status.setRollbackOnly();
                return new Blocked(Set.of(), List.of(), List.of(), true, capHolds);
            }
            for (EventSeat seat : heldSeats) {
                seats.writeLockedSeat(seat);
            }
            return new Inserted();
        });
    }

    /**
     * Closes the holds blocking an insert that Redis no longer has, each in its own transaction.
     * <p>
     * <b>Load-epoch guard</b> (M2.2c, the same as reconciler rule R2): the epoch is read before any Redis
     * check and compared again before each close. If Redis was wiped and reloaded in between, a hold
     * that looked absent may have just been restored, so this stops and closes nothing more; the retry
     * then decides with Redis as it is now.
     * <p>
     * <b>Progress</b> (review follow-up, round 711): a blocking hold that Redis no longer has counts as
     * progress whoever closes it in Postgres: this writer ({@code Released}) or another path at the same
     * moment, such as the other consumer or reconciler R2 ({@code NotActive}). Either way the block it
     * caused is gone, and the next insert sees that.
     *
     * @return whether at least one blocking hold was gone from Redis and is now closed in Postgres
     */
    private boolean closeHoldsRedisNoLongerHas(HoldFacts hold, Blocked blocked) {
        Optional<String> epoch = store.loadEpoch(hold.eventId());
        if (epoch.isEmpty()) {
            return false;   // not loaded: "Redis doesn't have it" means nothing (closeGone would refuse too)
        }
        boolean progress = false;
        Set<UUID> candidates = new LinkedHashSet<>(blocked.heldBy());
        candidates.addAll(blocked.capHolds());
        for (UUID other : candidates) {
            if (store.liveHold(hold.eventId(), other).isPresent()) {
                continue;
            }
            beforeEpochCheck.run();
            if (!epoch.equals(store.loadEpoch(hold.eventId()))) {
                log.warn("Event {} was reloaded into Redis while persisting hold {}; not closing older holds",
                        hold.eventId(), hold.holdId());
                return progress;
            }
            ReleaseOutcome outcome = closeGone(hold.eventId(), other);
            if (outcome instanceof ReleaseOutcome.Released || outcome instanceof ReleaseOutcome.NotActive) {
                progress = true;
            }
        }
        return progress;
    }

    /**
     * The user's other ACTIVE holds of this event: the holds the cap counts. ORDER BY id: a fixed order
     * to check and close them in (each close is its own transaction, so the order doesn't matter for
     * locking); it makes runs and tests reproducible.
     */
    private List<UUID> activeHoldsOfUser(HoldFacts hold) {
        return jdbc.sql("""
                        SELECT id FROM holds
                        WHERE event_id = :eventId AND user_id = :userId AND status = 'ACTIVE' AND id <> :holdId
                        ORDER BY id
                        """)
                .param("eventId", hold.eventId())
                .param("userId", hold.userId())
                .param("holdId", hold.holdId())
                .query(UUID.class)
                .list();
    }

    /** For tests: interleave a Redis wipe and reload at the one moment the epoch guard protects. */
    void beforeEpochCheck(Runnable hook) {
        this.beforeEpochCheck = hook;
    }

    /** For tests: interleave another path's close between a blocked insert and this writer's closes. */
    void afterBlocked(Runnable hook) {
        this.afterBlocked = hook;
    }

    /**
     * Postgres disagrees with a live Redis hold and wins (D-022): a seat is sold or held by a hold Redis
     * still has, a seat isn't in the event, or the cap is full. The hold is recorded as RELEASED, sold
     * seats get their marker back in Redis (and their buyers' {@code +inf} cap members), and the hold is
     * released in Redis. The marker goes first:
     * release.lua deletes only seat keys that still name this hold, so the sold seats stay taken.
     */
    private Applied postgresWins(HoldFacts hold, Blocked blocked) {
        log.warn("Redis hold {} of event {} conflicts with Postgres (held by {}, sold {}, unknown {}, over cap {}); "
                        + "Postgres wins and the hold is released in Redis", hold.holdId(), hold.eventId(),
                blocked.heldBy(), blocked.soldSeats(), blocked.unknownSeats(), blocked.overCap());
        insertRow(hold, "RELEASED");
        if (!blocked.soldSeats().isEmpty()) {
            List<SoldSeat> sold = soldSeats(hold.eventId(), blocked.soldSeats());
            for (SoldSeat seat : sold) {
                store.markSold(hold.eventId(), seat);
            }
            // The buyers' caps count those seats too, as R3 would repair later (M2.2c-2, found by
            // invariant 5): Redis didn't know the seats were sold, so it didn't count them either.
            for (SoldMember member : soldMembers(hold.eventId(), sold.stream().map(SoldSeat::orderId).distinct().toList())) {
                if (!store.hasSoldMember(hold.eventId(), member)) {
                    store.addSoldMember(hold.eventId(), member);
                }
            }
        }
        store.release(hold.eventId(), hold.holdId(), ReleaseReason.RELEASED);
        return Applied.CONFLICT;
    }

    // ---- SQL ----

    /**
     * Inserts the hold row with Redis' times. Returns false if a row with this id already exists.
     * {@code ON CONFLICT DO NOTHING} without a target (M2.2c-2): with {@code (id)}, Postgres absorbs only
     * conflicts on the primary key, and two concurrent inserts of one hold could fail on the table's other
     * unique constraint, {@code uq_holds_id_event (id, event_id)}, with 23505 instead. Both constraints are
     * keyed on the id, so any conflict still means "this hold's row exists".
     */
    private boolean insertRow(HoldFacts hold, String status) {
        int inserted = jdbc.sql("""
                        INSERT INTO holds (id, event_id, user_id, seat_ids, created_at, expires_at, status)
                        VALUES (:holdId, :eventId, :userId, :seatIds, :createdAt, :expiresAt, :status)
                        ON CONFLICT DO NOTHING
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

    /** The bought seats of these orders, per buyer and hold, as R3 counts them ({@code <holdId>:<seats>}, +inf). */
    private List<SoldMember> soldMembers(UUID eventId, List<UUID> orderIds) {
        return jdbc.sql("""
                        SELECT o.user_id, o.hold_id, count(*) AS seats
                        FROM event_seats es JOIN orders o ON o.id = es.order_id
                        WHERE es.event_id = :eventId AND es.status = 'SOLD' AND es.order_id IN (:orderIds)
                        GROUP BY o.user_id, o.hold_id
                        """)
                .param("eventId", eventId)
                .param("orderIds", orderIds)
                .query((rs, n) -> new SoldMember(rs.getObject("user_id", UUID.class),
                        rs.getObject("hold_id", UUID.class), rs.getInt("seats")))
                .list();
    }

    /** EXPIRED if the hold's time has run out by Redis' clock, otherwise RELEASED. */
    private String statusOfGoneHold(Instant expiresAt) {
        return expiresAt.toEpochMilli() <= store.nowMillis() ? "EXPIRED" : "RELEASED";
    }
}
