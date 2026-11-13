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

    /** Deletes every Redis key of this event under the context's prefix (never another context's). */
    public static void deleteEventKeys(ApplicationContext context, UUID eventId) {
        TestRedis.deleteKeys(context.getBean(StringRedisTemplate.class), store(context).keys().all() + "ev:" + eventId);
    }

    private static RedisHoldStore store(ApplicationContext context) {
        return context.getBean(RedisHoldStore.class);
    }
}
