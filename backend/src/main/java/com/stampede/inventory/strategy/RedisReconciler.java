package com.stampede.inventory.strategy;

import com.stampede.inventory.strategy.RedisHoldStore.LiveHold;
import com.stampede.inventory.strategy.RedisHoldStore.SoldMember;
import com.stampede.inventory.strategy.RedisHoldStore.SoldSeat;
import com.stampede.inventory.strategy.StreamEntry.HoldFacts;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Repairs drift between Redis and Postgres for REDIS_FIRST events (PRD 6.10, D-022, M2.2b). The
 * stream consumer is the normal path; this is the safety net behind it (lost or dead-lettered entries,
 * a Redis restart, a bug). Postgres wins every conflict; a live hold only Redis has is lag, not a
 * conflict, and is rolled forward.
 * <p>
 * Rules, per open REDIS_FIRST event:
 * <ul>
 *   <li><b>R0 load:</b> Redis doesn't have the event (meta missing): load it, and do nothing else for
 *       this event in this pass.</li>
 *   <li><b>R1 roll forward:</b> a Redis hold with no Postgres row, older than {@code min-age} by
 *       Redis' clock: persist it ({@link RedisHoldWriter#applyHeld}).</li>
 *   <li><b>R2 close:</b> a Postgres ACTIVE hold Redis no longer has: close it, EXPIRED or RELEASED by
 *       Redis' clock ({@link RedisHoldWriter#closeGone}).</li>
 *   <li><b>R3 sold:</b> a Postgres SOLD seat whose Redis key isn't its {@code S:<orderId>} marker: write
 *       the marker (and release in Redis any hold the key named); a bought order not counted in the
 *       buyer's Redis cap: add its {@code +inf} member.</li>
 *   <li><b>R4 release in Redis:</b> a Redis hold whose Postgres row exists but isn't ACTIVE: release it
 *       in Redis.</li>
 * </ul>
 * <b>Not racing the consumer.</b> Every rule goes through the same idempotent, lock-ordered writer
 * methods as the consumer, so if both do the same work the second is a no-op. R1's minimum age leaves
 * fresh holds to the consumer. R2 re-reads Redis for each hold, never trusting the earlier scan, and
 * compares the load epoch before every close: if Redis was wiped and reloaded during the pass, the
 * pass stops instead of closing holds the reload just restored.
 */
@Component
class RedisReconciler {

    private static final Logger log = LoggerFactory.getLogger(RedisReconciler.class);

    private final JdbcClient jdbc;
    private final RedisHoldStore store;
    private final RedisHoldWriter writer;
    private final RedisEventLoader loader;
    private final RedisStreamMetrics metrics;
    private final long minAgeMillis;

    RedisReconciler(JdbcClient jdbc, RedisHoldStore store, RedisHoldWriter writer, RedisEventLoader loader,
                    RedisStreamMetrics metrics, RedisInventoryProperties properties) {
        this.jdbc = jdbc;
        this.store = store;
        this.writer = writer;
        this.loader = loader;
        this.metrics = metrics;
        this.minAgeMillis = properties.reconciler().minAge().toMillis();
    }

    /** Repairs made by one pass (or one event). */
    record Repairs(int loaded, int rolledForward, int closed, int soldMarkers, int soldMembers,
                   int releasedInRedis, int failedEvents) {

        static final Repairs NONE = new Repairs(0, 0, 0, 0, 0, 0, 0);

        Repairs plus(Repairs other) {
            return new Repairs(loaded + other.loaded, rolledForward + other.rolledForward, closed + other.closed,
                    soldMarkers + other.soldMarkers, soldMembers + other.soldMembers,
                    releasedInRedis + other.releasedInRedis, failedEvents + other.failedEvents);
        }

        int total() {
            return loaded + rolledForward + closed + soldMarkers + soldMembers + releasedInRedis;
        }
    }

    /** One pass over every open REDIS_FIRST event. One failing event doesn't stop the others. */
    Repairs reconcileOnce() {
        Repairs total = Repairs.NONE;
        for (UUID eventId : loader.openRedisEvents()) {
            try {
                total = total.plus(reconcile(eventId));
            } catch (RuntimeException e) {
                log.error("Reconciling event {} failed", eventId, e);
                total = total.plus(new Repairs(0, 0, 0, 0, 0, 0, 1));
            }
        }
        if (total.total() + total.failedEvents() > 0) {
            log.info("Redis reconciler: {}", total);
        }
        return total;
    }

    /** One event, rules R0 to R4. */
    Repairs reconcile(UUID eventId) {
        Counter counter = new Counter();
        // R0
        Optional<String> epoch = store.loadEpoch(eventId);
        if (epoch.isEmpty()) {
            if (loader.ensureLoaded(eventId)) {
                counter.loaded++;
                metrics.repair("R0_load");
            }
            return counter.repairs();
        }

        long now = store.nowMillis();
        List<UUID> redisHolds = store.liveHoldIds(eventId);
        Map<UUID, String> postgresStatus = statuses(redisHolds);
        for (UUID holdId : redisHolds) {
            Optional<LiveHold> live = store.liveHold(eventId, holdId);
            if (live.isEmpty()) {
                continue;   // expired or released since the scan
            }
            String status = postgresStatus.get(holdId);
            if (status == null) {
                // R1: lag, or an entry that was lost or dead-lettered.
                if (live.get().createdAt().toEpochMilli() <= now - minAgeMillis) {
                    RedisHoldWriter.Applied applied = writer.applyHeld(HoldFacts.of(eventId, live.get()));
                    if (applied != RedisHoldWriter.Applied.DUPLICATE) {
                        counter.rolledForward++;
                        metrics.repair("R1_roll_forward");
                    }
                }
            } else if (!status.equals("ACTIVE")) {
                // R4: Postgres has closed it; Postgres wins.
                if (store.release(eventId, holdId, ReleaseReason.RELEASED) instanceof RedisHoldStore.Release.Released) {
                    counter.releasedInRedis++;
                    metrics.repair("R4_release_in_redis");
                }
            }
        }

        // R2
        for (UUID holdId : activeHolds(eventId)) {
            if (store.liveHold(eventId, holdId).isPresent()) {
                continue;
            }
            if (!epoch.equals(store.loadEpoch(eventId))) {
                log.warn("Event {} was reloaded into Redis during reconciliation; stopping this pass", eventId);
                return counter.repairs();
            }
            if (writer.closeGone(eventId, holdId) instanceof ReleaseOutcome.Released) {
                counter.closed++;
                metrics.repair("R2_close");
            }
        }

        // R3
        for (SoldSeat sold : soldSeats(eventId)) {
            String value = store.seatValue(eventId, sold.seatId());
            if (value != null && value.startsWith("S:" + sold.orderId() + ":")) {
                continue;
            }
            store.markSold(eventId, sold);
            counter.soldMarkers++;
            metrics.repair("R3_sold_marker");
            if (value != null && value.startsWith("H:")) {
                UUID holder = UUID.fromString(value.split(":")[1]);
                if (store.release(eventId, holder, ReleaseReason.RELEASED) instanceof RedisHoldStore.Release.Released) {
                    counter.releasedInRedis++;
                }
            }
        }
        for (SoldMember member : soldMembers(eventId)) {
            if (!store.hasSoldMember(eventId, member)) {
                store.addSoldMember(eventId, member);
                counter.soldMembers++;
                metrics.repair("R3_sold_member");
            }
        }
        return counter.repairs();
    }

    private static final class Counter {
        int loaded;
        int rolledForward;
        int closed;
        int soldMarkers;
        int soldMembers;
        int releasedInRedis;

        Repairs repairs() {
            return new Repairs(loaded, rolledForward, closed, soldMarkers, soldMembers, releasedInRedis, 0);
        }
    }

    private Map<UUID, String> statuses(List<UUID> holdIds) {
        Map<UUID, String> statuses = new HashMap<>();
        if (holdIds.isEmpty()) {
            return statuses;
        }
        jdbc.sql("SELECT id, status FROM holds WHERE id = ANY(:ids)")
                .param("ids", holdIds.toArray(UUID[]::new))
                .query((rs, n) -> statuses.put(rs.getObject("id", UUID.class), rs.getString("status")))
                .list();
        return statuses;
    }

    private List<UUID> activeHolds(UUID eventId) {
        return jdbc.sql("SELECT id FROM holds WHERE event_id = :eventId AND status = 'ACTIVE' ORDER BY id")
                .param("eventId", eventId)
                .query(UUID.class)
                .list();
    }

    private List<SoldSeat> soldSeats(UUID eventId) {
        return jdbc.sql("""
                        SELECT es.seat_id, es.order_id, o.created_at
                        FROM event_seats es JOIN orders o ON o.id = es.order_id
                        WHERE es.event_id = :eventId AND es.status = 'SOLD'
                        """)
                .param("eventId", eventId)
                .query((rs, n) -> new SoldSeat(rs.getObject("seat_id", UUID.class), rs.getObject("order_id", UUID.class),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant()))
                .list();
    }

    private List<SoldMember> soldMembers(UUID eventId) {
        return jdbc.sql("""
                        SELECT o.user_id, o.hold_id, count(*) AS seats
                        FROM event_seats es JOIN orders o ON o.id = es.order_id
                        WHERE es.event_id = :eventId AND es.status = 'SOLD'
                        GROUP BY o.user_id, o.hold_id
                        """)
                .param("eventId", eventId)
                .query((rs, n) -> new SoldMember(rs.getObject("user_id", UUID.class),
                        rs.getObject("hold_id", UUID.class), rs.getInt("seats")))
                .list();
    }
}
