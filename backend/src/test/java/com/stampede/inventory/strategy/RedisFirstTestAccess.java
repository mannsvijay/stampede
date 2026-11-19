package com.stampede.inventory.strategy;

import com.stampede.testsupport.TestRedis;
import java.util.UUID;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Test-only: lets HTTP tests in other packages look at an application context's Redis inventory keys
 * (always under that context's own key prefix) and clean them up. The Redis classes are package-private
 * in {@code inventory.strategy}; this class lives in the same package (test sources) to reach them.
 */
public final class RedisFirstTestAccess {

    private RedisFirstTestAccess() {
    }

    /** The raw value of a seat key ({@code H:...} or {@code S:...}), or null if the seat is free in Redis. */
    public static String seatValue(ApplicationContext context, UUID eventId, UUID seatId) {
        return store(context).seatValue(eventId, seatId);
    }

    /** Whether Redis still has the hold's hash (a live or pinned hold). */
    public static boolean holdExists(ApplicationContext context, UUID eventId, UUID holdId) {
        return store(context).liveHold(eventId, holdId).isPresent();
    }

    /** Whether the user's cap in Redis counts this hold's bought seats forever (member score +inf). */
    public static boolean boughtSeatsCounted(ApplicationContext context, UUID eventId, UUID userId, UUID holdId,
                                             int seatCount) {
        return store(context).hasSoldMember(eventId, new RedisHoldStore.SoldMember(userId, holdId, seatCount));
    }

    /**
     * Ends a live hold now, as its TTL would at its expiry millisecond (no script runs then): its seat keys
     * and hash expire, and its cap member's score (its expiry) is moved to now. HTTP tests can't wait out
     * the real 5-minute hold. If Postgres has the hold, its {@code expires_at} becomes the same millisecond,
     * as the consumer would have written it for a hold of that length; its status stays ACTIVE (the sweeper
     * hasn't run), exactly as after a real expiry.
     */
    public static void expireNow(ApplicationContext context, UUID eventId, UUID holdId) {
        RedisHoldStore store = store(context);
        StringRedisTemplate redis = context.getBean(StringRedisTemplate.class);
        RedisHoldStore.LiveHold hold = store.liveHold(eventId, holdId).orElseThrow();
        long now = store.nowMillis();
        context.getBean(org.springframework.jdbc.core.simple.JdbcClient.class)
                .sql("UPDATE holds SET expires_at = ? WHERE id = ?")
                .params(java.time.Instant.ofEpochMilli(now).atOffset(java.time.ZoneOffset.UTC), holdId)
                .update();
        redis.opsForZSet().add(store.keys().user(eventId, hold.userId()), holdId + ":" + hold.seatIds().size(), now);
        for (UUID seat : hold.seatIds()) {
            redis.expire(store.keys().seat(eventId, seat), java.time.Duration.ofMillis(1));
        }
        redis.expire(store.keys().hold(eventId, holdId), java.time.Duration.ofMillis(1));
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(2))
                .until(() -> store.liveHold(eventId, holdId).isEmpty()
                        && hold.seatIds().stream().allMatch(seat -> store.seatValue(eventId, seat) == null));
    }

    /** Deletes one seat key, as if Redis had lost it (a crash between AOF fsyncs, D-022). */
    public static void deleteSeatKey(ApplicationContext context, UUID eventId, UUID seatId) {
        context.getBean(StringRedisTemplate.class).delete(store(context).keys().seat(eventId, seatId));
    }

    /** Sets the event's closed gate, as a strategy switch away from REDIS_FIRST does first (D-023). */
    public static void close(ApplicationContext context, UUID eventId) {
        store(context).close(eventId);
    }

    /** Deletes every Redis key of this event under the context's prefix (never another context's). */
    public static void deleteEventKeys(ApplicationContext context, UUID eventId) {
        TestRedis.deleteKeys(context.getBean(StringRedisTemplate.class), store(context).keys().all() + "ev:" + eventId);
    }

    /**
     * Applies every entry of the inventory stream to Postgres, as the consumer thread (off in tests)
     * would: new entries, then anything left pending, until a round handles none. Entries of events that
     * no longer exist or aren't REDIS_FIRST are acknowledged with nothing written (the consumer guard).
     */
    public static void drainStream(ApplicationContext context) {
        RedisStreamConsumer consumer = context.getBean(RedisStreamConsumer.class);
        consumer.ensureGroup();
        int handled;
        do {
            handled = consumer.pollOnce("test-drain") + consumer.reclaimOnce("test-drain");
        } while (handled > 0);
    }

    private static RedisHoldStore store(ApplicationContext context) {
        return context.getBean(RedisHoldStore.class);
    }
}
