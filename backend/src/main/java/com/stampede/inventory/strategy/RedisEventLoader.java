package com.stampede.inventory.strategy;

import com.stampede.inventory.strategy.RedisHoldStore.EventSnapshot;
import com.stampede.inventory.strategy.RedisHoldStore.LiveHold;
import com.stampede.inventory.strategy.RedisHoldStore.Load;
import com.stampede.inventory.strategy.RedisHoldStore.SoldMember;
import com.stampede.inventory.strategy.RedisHoldStore.SoldSeat;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Loads REDIS_FIRST events from Postgres into Redis (D-022, M2.2b): seats, per-user limit, SOLD
 * markers, bought seats for the cap, and unexpired ACTIVE holds. Runs on the reconciler's first pass
 * at startup, on every pass for an event whose meta is missing (a Redis restart or wipe), and (M2.2c)
 * when a hold attempt gets NOT_LOADED.
 * <p>
 * The Postgres side is read in one read-only REPEATABLE READ transaction, so every query sees the same
 * snapshot. The Redis side is one script ({@code redis/load.lua}) that writes everything or nothing,
 * and nothing at all if the event is already loaded.
 */
@Component
class RedisEventLoader {

    private static final Logger log = LoggerFactory.getLogger(RedisEventLoader.class);

    private final JdbcClient jdbc;
    private final RedisHoldStore store;
    private final TransactionTemplate snapshotTransaction;

    RedisEventLoader(JdbcClient jdbc, RedisHoldStore store, PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.store = store;
        this.snapshotTransaction = new TransactionTemplate(transactions);
        snapshotTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        snapshotTransaction.setReadOnly(true);
    }

    /** The REDIS_FIRST events that take holds or may still change: neither DRAFT nor ENDED. */
    List<UUID> openRedisEvents() {
        return jdbc.sql("""
                        SELECT id FROM events
                        WHERE strategy = 'REDIS_FIRST' AND status NOT IN ('DRAFT', 'ENDED')
                        ORDER BY id
                        """)
                .query(UUID.class)
                .list();
    }

    /**
     * Loads the event unless Redis has it already. Safe to call from many threads and instances at once:
     * the script checks and writes in one atomic step, so only the first load writes.
     *
     * @return true if this call loaded it
     */
    boolean ensureLoaded(UUID eventId) {
        if (store.isLoaded(eventId)) {
            return false;
        }
        EventSnapshot snapshot = snapshotTransaction.execute(status -> snapshot(eventId));
        Load result = store.load(eventId, snapshot);
        if (result instanceof Load.Loaded loaded) {
            log.info("Loaded event {} into Redis: {} seats, {} sold, {} holds restored, {} already expired",
                    eventId, snapshot.seatIds().size(), snapshot.soldSeats().size(), loaded.restoredHolds(),
                    loaded.skippedHolds());
            return true;
        }
        return false;
    }

    private EventSnapshot snapshot(UUID eventId) {
        int limit = jdbc.sql("SELECT per_user_limit FROM events WHERE id = :eventId")
                .param("eventId", eventId)
                .query(Integer.class)
                .optional()
                .orElseThrow(() -> new IllegalArgumentException("No event " + eventId));
        List<UUID> seatIds = jdbc.sql("SELECT seat_id FROM event_seats WHERE event_id = :eventId ORDER BY seat_id")
                .param("eventId", eventId)
                .query(UUID.class)
                .list();
        // The order's creation time stands in for the moment of sale (takenAgoMs, as in D-016).
        List<SoldSeat> sold = jdbc.sql("""
                        SELECT es.seat_id, es.order_id, o.created_at
                        FROM event_seats es JOIN orders o ON o.id = es.order_id
                        WHERE es.event_id = :eventId AND es.status = 'SOLD'
                        """)
                .param("eventId", eventId)
                .query((rs, n) -> new SoldSeat(rs.getObject("seat_id", UUID.class), rs.getObject("order_id", UUID.class),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant()))
                .list();
        // Bought seats count towards the buyer's cap forever (D-016), per order (= per hold, V3).
        List<SoldMember> members = jdbc.sql("""
                        SELECT o.user_id, o.hold_id, count(*) AS seats
                        FROM event_seats es JOIN orders o ON o.id = es.order_id
                        WHERE es.event_id = :eventId AND es.status = 'SOLD'
                        GROUP BY o.user_id, o.hold_id
                        """)
                .param("eventId", eventId)
                .query((rs, n) -> new SoldMember(rs.getObject("user_id", UUID.class),
                        rs.getObject("hold_id", UUID.class), rs.getInt("seats")))
                .list();
        // ACTIVE holds; the script skips any whose time has run out by Redis' clock.
        List<LiveHold> holds = jdbc.sql("""
                        SELECT id, user_id, seat_ids, created_at, expires_at
                        FROM holds
                        WHERE event_id = :eventId AND status = 'ACTIVE'
                        """)
                .param("eventId", eventId)
                .query((rs, n) -> new LiveHold(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
                        SeatRows.uuids(rs, "seat_ids"),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("expires_at", OffsetDateTime.class).toInstant()))
                .list();
        return new EventSnapshot(limit, seatIds, sold, members, holds);
    }
}
