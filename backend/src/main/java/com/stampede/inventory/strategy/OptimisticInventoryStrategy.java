package com.stampede.inventory.strategy;

import com.stampede.inventory.seat.EventSeat;
import com.stampede.inventory.seat.IllegalSeatTransitionException;
import com.stampede.inventory.strategy.HoldOutcome.Held;
import com.stampede.inventory.strategy.HoldOutcome.Rejected;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Strategy B (PRD 6.2, M2.1, D-021): read the seats without locking them, let {@link EventSeat}
 * decide, then write each seat with {@code UPDATE ... WHERE version = <the version read>}. If any
 * UPDATE matches zero rows, another buyer changed that seat after we read it: the whole try is rolled
 * back (all-or-nothing) and retried a bounded number of times with jittered backoff.
 * <p>
 * <b>Transactions.</b> {@link #hold} is not one transaction: each try is its own, run through a
 * {@link TransactionTemplate}, and the backoff sleep happens between them with no transaction and no
 * connection held. {@code Propagation.NEVER} makes a caller's open transaction an error instead of
 * something to join (D-015 says callers must not open one; here a joined transaction couldn't roll
 * back one try without rolling back the caller).
 * <p>
 * <b>Lock order.</b> Optimistic still takes row locks: an UPDATE locks its row until commit. Seats are
 * written one UPDATE at a time in the order the read returned them, which is Postgres
 * {@code ORDER BY seat_id}, so every path locks seats in the same order as the pessimistic strategy,
 * release, sell and the sweeper (D-015), and two tries on overlapping seats queue instead of
 * deadlocking. The ids are never sorted in Java (UUID.compareTo disagrees with Postgres). The
 * allowance row comes after the seats, as everywhere.
 * <p>
 * Release and sell lock the hold row first and are shared with the pessimistic strategy
 * ({@link HoldClosing}).
 */
@Component
class OptimisticInventoryStrategy implements InventoryStrategy {

    private final JdbcClient jdbc;
    private final SeatRows seats;
    private final HoldClosing closing;
    private final HoldMetrics metrics;
    private final OptimisticRetryProperties retry;
    private final TransactionTemplate transactions;

    OptimisticInventoryStrategy(JdbcClient jdbc, SeatRows seats, HoldClosing closing, HoldMetrics metrics,
            OptimisticRetryProperties retry, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.seats = seats;
        this.closing = closing;
        this.metrics = metrics;
        this.retry = retry;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Override
    public StrategyKind kind() {
        return StrategyKind.OPTIMISTIC;
    }

    /**
     * Up to {@code max-attempts} tries. A taken seat (HELD or SOLD when read) is a real answer and is
     * never retried; only a lost version check is. When every try lost one, the answer is
     * {@code CONTENDED} with the seat the last try lost on. Nothing in this method runs SQL outside
     * the template: a statement here would hold a connection across the backoff sleep.
     */
    @Override
    @Transactional(propagation = Propagation.NEVER)
    public HoldOutcome hold(HoldRequest request) {
        long start = System.nanoTime();
        HoldOutcome outcome = null;
        try {
            outcome = holdWithRetries(request);
            return outcome;
        } finally {
            metrics.finished(kind(), outcome, System.nanoTime() - start);
        }
    }

    private HoldOutcome holdWithRetries(HoldRequest request) {
        UUID lostOn = null;
        for (int attempt = 1; attempt <= retry.maxAttempts(); attempt++) {
            if (attempt > 1 && !backOff(attempt)) {
                break;  // interrupted (shutting down): stop retrying, nothing was written
            }
            metrics.attempt(kind());
            try {
                // A new transaction per try. It commits when the callback returns (a Rejected outcome
                // has either written nothing or marked itself rollback-only) and rolls back when it
                // throws; VersionConflict is that throw.
                return transactions.execute(status -> attempt(request, status));
            } catch (VersionConflict conflict) {
                metrics.conflict(kind());
                lostOn = conflict.seatId;
            }
        }
        return new Rejected(HoldRejection.CONTENDED, List.of(lostOn));
    }

    /** One try, inside its own transaction. */
    private HoldOutcome attempt(HoldRequest request, TransactionStatus transaction) {
        // Step O1: read the seats WITHOUT locking them, in Postgres seat_id order (the order they will
        // be written in). Each row carries its owner's created_at and the database clock, read in the
        // same statement, for takenAgoMs (D-016): one snapshot, so they agree with the seat's state.
        List<ReadSeat> read = readSeats(request.eventId(), request.seatIds());

        // Step O2: an id with no row isn't a seat of this event. Precedence as in D-015.
        Set<UUID> found = new HashSet<>();
        for (ReadSeat row : read) {
            found.add(row.seat().seatId());
        }
        List<UUID> unknown = request.seatIds().stream().filter(id -> !found.contains(id)).toList();
        if (!unknown.isEmpty()) {
            return new Rejected(HoldRejection.UNKNOWN_SEAT, unknown);
        }

        // Step O3: the state machine decides for every seat before anything is written. A seat that
        // is HELD or SOLD in committed data is a real rejection, not a race: no retry.
        List<EventSeat> heldSeats = new ArrayList<>();
        List<ReadSeat> taken = new ArrayList<>();
        for (ReadSeat row : read) {
            try {
                heldSeats.add(row.seat().hold(request.holdId()));
            } catch (IllegalSeatTransitionException e) {
                taken.add(row);
            }
        }
        if (!taken.isEmpty()) {
            return new Rejected(HoldRejection.SEAT_TAKEN, taken.stream().map(r -> r.seat().seatId()).toList(),
                    takenAgoMs(taken));
        }

        // Step O4: the hold row first, because event_seats.hold_id has a foreign key to holds. It is a
        // new row nobody else can see or wait for, so it adds nothing to the lock order. A retried try
        // reuses the same holdId: the previous try's insert was rolled back.
        Instant expiresAt = seats.insertHold(request);

        // Step O5: write each seat only if it is still at the version we read, in the read's
        // (seat_id) order. Each UPDATE locks its row as it runs, so this loop IS the lock order.
        // Zero rows = someone else changed the seat since step O1: throw, which rolls back the hold
        // row and every seat already written in this try.
        for (EventSeat seat : heldSeats) {
            if (!seats.writeSeatIfUnchanged(seat)) {
                throw new VersionConflict(seat.seatId());
            }
        }

        // Step O6: the per-user cap, after the seats (the allowance row is last in the lock order,
        // D-015). Over the limit: undo the hold row and seat writes, and answer LIMIT_EXCEEDED.
        if (!seats.addToAllowance(request)) {
            transaction.setRollbackOnly();
            return new Rejected(HoldRejection.LIMIT_EXCEEDED, request.seatIds());
        }

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

    /**
     * Step O1. A plain SELECT takes no row locks. The LEFT JOINs read (not lock) the current owner: a
     * HELD seat's hold, a SOLD seat's order (D-014). clock_timestamp() is the time of this statement,
     * not of the transaction's start.
     */
    private List<ReadSeat> readSeats(UUID eventId, List<UUID> seatIds) {
        return jdbc.sql("""
                        SELECT es.event_id, es.seat_id, es.status, es.hold_id, es.order_id, es.version,
                               coalesce(h.created_at, o.created_at) AS owner_since,
                               clock_timestamp() AS read_at
                        FROM event_seats es
                        LEFT JOIN holds h ON h.id = es.hold_id
                        LEFT JOIN orders o ON o.id = es.order_id
                        WHERE es.event_id = :eventId AND es.seat_id IN (:seatIds)
                        ORDER BY es.seat_id
                        """)
                .param("eventId", eventId)
                .param("seatIds", seatIds)
                .query((rs, rowNum) -> new ReadSeat(SeatRows.toEventSeat(rs, rowNum),
                        instantOrNull(rs.getObject("owner_since", OffsetDateTime.class)),
                        rs.getObject("read_at", OffsetDateTime.class).toInstant()))
                .list();
    }

    /** How long ago the most recently taken of these seats was taken (D-016); never negative. */
    private static long takenAgoMs(List<ReadSeat> taken) {
        Instant newestOwner = null;
        Instant readAt = null;
        for (ReadSeat row : taken) {
            readAt = row.readAt();
            if (row.ownerSince() != null && (newestOwner == null || row.ownerSince().isAfter(newestOwner))) {
                newestOwner = row.ownerSince();
            }
        }
        if (newestOwner == null) {
            return 0;  // can't happen: ck_event_seats_status_pointers gives every HELD/SOLD seat an owner
        }
        return Math.max(0, Duration.between(newestOwner, readAt).toMillis());
    }

    /**
     * Sleeps before try {@code attempt} (full jitter, see {@link OptimisticRetryProperties}). Runs
     * between transactions, so no connection or row lock is held while sleeping.
     *
     * @return false if the thread was interrupted (its flag is restored)
     */
    private boolean backOff(int attempt) {
        long capMillis = retry.backoffCapMillis(attempt);
        if (capMillis <= 0) {
            return true;
        }
        try {
            Thread.sleep(ThreadLocalRandom.current().nextLong(capMillis + 1));
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static Instant instantOrNull(OffsetDateTime time) {
        return time == null ? null : time.toInstant();
    }

    /** A seat as read in step O1, with its current owner's created_at (null while AVAILABLE). */
    private record ReadSeat(EventSeat seat, Instant ownerSince, Instant readAt) {
    }

    /**
     * A lost version check: thrown inside the try's transaction so the template rolls it back, and
     * caught by the retry loop. Expected under contention, so no stack trace is captured.
     */
    private static final class VersionConflict extends RuntimeException {
        private final UUID seatId;

        VersionConflict(UUID seatId) {
            super("Seat " + seatId + " changed since it was read", null, false, false);
            this.seatId = seatId;
        }
    }
}
