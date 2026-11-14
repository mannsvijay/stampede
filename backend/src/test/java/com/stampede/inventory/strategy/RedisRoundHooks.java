package com.stampede.inventory.strategy;

import com.stampede.testsupport.TestRedis;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * The harness's per-round steps for the Redis-first strategy (M2.2c-2). Each round starts from an empty
 * Redis prefix, just like it starts from an empty event in Postgres, and ends with the stream drained,
 * so every check (I4-I7 and the invariant checker, I10 included) compares the two stores strictly:
 * zero tolerance after draining (D-022).
 */
final class RedisRoundHooks implements ConcurrencyHarness.RoundHooks {

    private static final String CONSUMER = "harness";

    private final StringRedisTemplate redis;
    private final String prefix;
    private final RedisStreamConsumer consumer;
    private final RedisEventLoader loader;
    private final UUID eventId;

    RedisRoundHooks(StringRedisTemplate redis, String prefix, RedisStreamConsumer consumer, RedisEventLoader loader,
                    UUID eventId) {
        this.redis = redis;
        this.prefix = prefix;
        this.consumer = consumer;
        this.loader = loader;
        this.eventId = eventId;
    }

    /**
     * Deletes this context's keys (SCAN by prefix, never FLUSHDB: the test Redis is shared), recreates
     * the consumer group and loads the event. Loading here, not lazily on the first NOT_LOADED, keeps the
     * race about holds; the per-user limit the scenario just set is read by this load.
     */
    @Override
    public void afterReset() {
        TestRedis.deleteKeys(redis, prefix);
        consumer.ensureGroup();
        if (!loader.ensureLoaded(eventId)) {
            throw new IllegalStateException("event " + eventId + " was already loaded after the reset");
        }
    }

    /** Applies every stream entry to Postgres: new entries, then anything left pending. */
    @Override
    public void beforeChecks() {
        int handled;
        do {
            handled = consumer.pollOnce(CONSUMER) + consumer.reclaimOnce(CONSUMER);
        } while (handled > 0);
    }
}
