package com.stampede.inventory.strategy;

import com.stampede.inventory.api.EventStrategy;
import com.stampede.inventory.api.StrategySwitch;
import com.stampede.inventory.api.SwitchResult;
import com.stampede.inventory.api.SwitchResult.Reason;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Switches an event's strategy (M2.3a, D-023, option A): only while the event takes no holds and
 * nothing is live, so no hold ever has to move from one strategy to another.
 * <p>
 * <b>The protocol</b>, for one event:
 * <ol>
 *   <li><b>One switch at a time.</b> A Postgres advisory lock for this event, held on a connection of
 *       its own for the whole switch; a second switch gets SWITCH_IN_PROGRESS at once.</li>
 *   <li><b>From REDIS_FIRST: close the gate in Redis</b> ({@code p:ev:<event>:closed}). Redis runs one
 *       command at a time, so every {@code hold.lua} either ran before (its hash and stream entry exist,
 *       and step 4 sees them) or answers CLOSED after.</li>
 *   <li><b>One transaction</b>: the event row ({@code FOR NO KEY UPDATE}), then every seat of the event
 *       ({@code ORDER BY seat_id FOR UPDATE}, D-015's order). A hold in flight either committed before
 *       (step 4 sees it) or waits for these locks and then fails its in-transaction re-check
 *       ({@link StrategyGuard}).</li>
 *   <li><b>Checks</b>, under those locks: not ENDED, not on sale, no ACTIVE hold; from REDIS_FIRST also
 *       no hold in Redis and no unapplied stream entry of the event. To REDIS_FIRST: delete any keys
 *       left from an earlier REDIS_FIRST period, because {@code load.lua} writes nothing into an event
 *       whose meta exists and would keep stale state. Then {@code UPDATE events}, the audit row, commit.</li>
 *   <li><b>After the commit</b>: from REDIS_FIRST, delete the event's keys except the gate, which stays
 *       so a loader whose snapshot predates the switch can't load the event back (decision 2a). To
 *       REDIS_FIRST, remove the gate and load the event.</li>
 * </ol>
 * A refusal or a failure before the commit removes the gate again: the event stays as it was.
 * <p>
 * <b>Why {@code FOR NO KEY UPDATE}, not {@code FOR UPDATE}:</b> inserting a hold row checks its
 * foreign key to {@code events} with a {@code FOR KEY SHARE} lock on the event row, which
 * {@code FOR UPDATE} blocks. A hold holding a seat lock and inserting its row would wait for the
 * switch, while the switch waits for that seat: a deadlock. {@code NO KEY UPDATE} (what an UPDATE of a
 * non-key column takes anyway) doesn't conflict with {@code KEY SHARE}.
 * <p>
 * <b>Recovery:</b> if the process dies after the commit, before step 5, repeat the request. From
 * REDIS_FIRST the leftover keys are inert (the gate is set) and the next switch to REDIS_FIRST
 * deletes them; to REDIS_FIRST the gate is still set, and a request for the strategy the event already
 * has removes it ({@link SwitchResult.Unchanged}).
 */
@Component
class StrategySwitcher implements StrategySwitch {

    private static final Logger log = LoggerFactory.getLogger(StrategySwitcher.class);

    private final DataSource dataSource;
    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;
    private final RedisHoldStore store;
    private final RedisStreamConsumer consumer;
    private final RedisEventLoader loader;
    /** Test hook only: runs inside the transaction once every lock is held, before the checks. */
    private volatile Runnable whileLocked = () -> { };

    StrategySwitcher(DataSource dataSource, JdbcClient jdbc, PlatformTransactionManager transactions,
                     RedisHoldStore store, RedisStreamConsumer consumer, RedisEventLoader loader) {
        this.dataSource = dataSource;
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactions);
        this.store = store;
        this.consumer = consumer;
        this.loader = loader;
    }

    @Override
    public SwitchResult switchStrategy(UUID eventId, EventStrategy target, AuditWriter audit) {
        StrategyKind to = StrategyKind.valueOf(target.name());
        try (SwitchLock lock = SwitchLock.tryAcquire(dataSource, eventId)) {
            Optional<StrategyKind> current = currentStrategy(eventId);
            if (current.isEmpty()) {
                return new SwitchResult.EventNotFound();
            }
            StrategyKind from = current.get();
            if (!lock.acquired()) {
                return new SwitchResult.Refused(Reason.SWITCH_IN_PROGRESS, api(from));
            }
            if (from == to) {
                removeLeftoverGate(eventId, to);
                return new SwitchResult.Unchanged(api(to));
            }
            boolean fromRedis = from == StrategyKind.REDIS_FIRST;
            if (fromRedis) {
                store.close(eventId);
            }
            SwitchResult result;
            try {
                result = transaction.execute(status -> {
                    SwitchResult inside = switchLocked(eventId, from, to, audit);
                    if (!(inside instanceof SwitchResult.Switched)) {
                        status.setRollbackOnly();   // nothing was written; this only releases the locks
                    }
                    return inside;
                });
            } catch (RuntimeException e) {
                if (fromRedis) {
                    store.reopen(eventId);
                }
                throw e;
            }
            if (!(result instanceof SwitchResult.Switched)) {
                if (fromRedis) {
                    store.reopen(eventId);
                }
                return result;
            }
            afterCommit(eventId, from, to);
            log.info("Event {} switched from {} to {}", eventId, from, to);
            return result;
        }
    }

    /** For tests: run something while the switch holds the event row and every seat lock. */
    void whileLocked(Runnable hook) {
        this.whileLocked = hook;
    }

    /** Steps 3 and 4, inside the transaction. Writes only when every check passes. */
    private SwitchResult switchLocked(UUID eventId, StrategyKind from, StrategyKind to, AuditWriter audit) {
        EventRow event = jdbc.sql("""
                        SELECT strategy, status, drop_at <= now() AS dropped
                        FROM events WHERE id = :eventId
                        FOR NO KEY UPDATE
                        """)
                .param("eventId", eventId)
                .query((rs, n) -> new EventRow(StrategyKind.valueOf(rs.getString("strategy")),
                        rs.getString("status"), rs.getBoolean("dropped")))
                .single();
        // Every seat of the event, in seat_id order like every other path (D-015). Not read, only locked.
        jdbc.sql("SELECT seat_id FROM event_seats WHERE event_id = :eventId ORDER BY seat_id FOR UPDATE")
                .param("eventId", eventId)
                .query(UUID.class)
                .list();
        whileLocked.run();

        if (event.strategy() != from) {
            // Can't happen while we hold the advisory lock: only a switch changes the strategy.
            return new SwitchResult.Refused(Reason.SWITCH_IN_PROGRESS, api(event.strategy()));
        }
        if (event.status().equals("ENDED")) {
            return new SwitchResult.Refused(Reason.EVENT_ENDED, api(from));
        }
        if (event.takesHolds()) {
            return new SwitchResult.Refused(Reason.ON_SALE, api(from));
        }
        long active = jdbc.sql("SELECT count(*) FROM holds WHERE event_id = :eventId AND status = 'ACTIVE'")
                .param("eventId", eventId)
                .query(Long.class)
                .single();
        if (active > 0) {
            return new SwitchResult.Refused(Reason.LIVE_HOLDS, api(from));
        }
        if (from == StrategyKind.REDIS_FIRST) {
            // The gate is closed, so no new hold can appear in Redis while we look.
            if (!store.liveHoldIds(eventId).isEmpty()) {
                return new SwitchResult.Refused(Reason.LIVE_REDIS_HOLDS, api(from));
            }
            if (consumer.hasUnappliedEntries(eventId)) {
                return new SwitchResult.Refused(Reason.UNAPPLIED_STREAM_ENTRIES, api(from));
            }
        }
        if (to == StrategyKind.REDIS_FIRST) {
            // Leftovers of an earlier REDIS_FIRST period would look like a loaded event (stale meta).
            long deleted = store.deleteEventKeys(eventId);
            if (deleted > 0) {
                log.info("Event {}: deleted {} leftover Redis keys before switching to REDIS_FIRST", eventId, deleted);
            }
        }
        jdbc.sql("UPDATE events SET strategy = :to WHERE id = :eventId")
                .param("to", to.name())
                .param("eventId", eventId)
                .update();
        audit.strategyChanged(eventId, api(from), api(to));
        return new SwitchResult.Switched(api(from), api(to));
    }

    /**
     * Step 5. The switch is committed, so a failure here is logged, not thrown: the answer stays true.
     * Repeating the request finishes it (see Recovery in the class comment).
     */
    private void afterCommit(UUID eventId, StrategyKind from, StrategyKind to) {
        try {
            if (from == StrategyKind.REDIS_FIRST) {
                long deleted = store.deleteEventKeys(eventId);
                log.info("Event {}: deleted {} Redis keys after switching away from REDIS_FIRST", eventId, deleted);
            }
            if (to == StrategyKind.REDIS_FIRST) {
                store.reopen(eventId);
                loader.ensureLoaded(eventId);
            }
        } catch (RuntimeException e) {
            log.error("Event {} switched from {} to {}, but the Redis clean-up after it failed; repeat the "
                    + "request to finish it", eventId, from, to, e);
        }
    }

    /** A REDIS_FIRST event with its gate still set (a switch to it died before removing it). */
    private void removeLeftoverGate(UUID eventId, StrategyKind strategy) {
        if (strategy == StrategyKind.REDIS_FIRST && store.isClosed(eventId)) {
            log.warn("Event {} is REDIS_FIRST but its Redis gate was still closed; reopening it", eventId);
            store.reopen(eventId);
            loader.ensureLoaded(eventId);
        }
    }

    private Optional<StrategyKind> currentStrategy(UUID eventId) {
        return jdbc.sql("SELECT strategy FROM events WHERE id = :eventId")
                .param("eventId", eventId)
                .query(String.class)
                .optional()
                .map(StrategyKind::valueOf);
    }

    private static EventStrategy api(StrategyKind kind) {
        return EventStrategy.valueOf(kind.name());
    }

    private record EventRow(StrategyKind strategy, String status, boolean dropped) {
        /** The same rule as HoldService and {@link StrategyGuard#takesHolds} (D-016). */
        boolean takesHolds() {
            return dropped && (status.equals("SCHEDULED") || status.equals("LIVE"));
        }
    }

    /**
     * The per-event advisory lock (step 1): a transaction-level lock taken on a connection of its own,
     * released when that transaction ends. {@link #close} rolls it back, and a connection that dies
     * ends it too, so the lock can't outlive the switch. An advisory lock is a lock on a number the
     * application chooses (here a hash of the event id), not on a row; a hash collision between two
     * events only means one waits for the other's switch.
     */
    private static final class SwitchLock implements AutoCloseable {

        private final Connection connection;
        private final boolean acquired;

        private SwitchLock(Connection connection, boolean acquired) {
            this.connection = connection;
            this.acquired = acquired;
        }

        static SwitchLock tryAcquire(DataSource dataSource, UUID eventId) {
            Connection connection = null;
            try {
                connection = dataSource.getConnection();
                connection.setAutoCommit(false);
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT pg_try_advisory_xact_lock(hashtextextended(?, 0))")) {
                    statement.setString(1, "stampede.strategy-switch:" + eventId);
                    try (ResultSet rs = statement.executeQuery()) {
                        rs.next();
                        return new SwitchLock(connection, rs.getBoolean(1));
                    }
                }
            } catch (SQLException e) {
                closeQuietly(connection);
                throw new DataAccessResourceFailureException("Could not take the strategy switch lock", e);
            }
        }

        boolean acquired() {
            return acquired;
        }

        @Override
        public void close() {
            try {
                connection.rollback();   // ends the transaction, and with it the advisory lock
                connection.setAutoCommit(true);
            } catch (SQLException e) {
                log.warn("Could not end the strategy switch lock's transaction; closing its connection", e);
            } finally {
                closeQuietly(connection);
            }
        }

        private static void closeQuietly(Connection connection) {
            if (connection == null) {
                return;
            }
            try {
                connection.close();
            } catch (SQLException e) {
                log.warn("Could not close the strategy switch lock's connection", e);
            }
        }
    }
}
