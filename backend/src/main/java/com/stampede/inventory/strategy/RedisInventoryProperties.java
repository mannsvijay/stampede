package com.stampede.inventory.strategy;

import java.time.Duration;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the Redis-first strategy (D-022), {@code stampede.inventory.redis.*}.
 *
 * @param keyPrefix  the first part of every inventory key in Redis. Two applications (or two Spring
 *                   test contexts) that share a Redis server must use different prefixes, or they
 *                   would see each other's holds
 * @param consumer   the stream consumer that persists holds to Postgres (M2.2b)
 * @param reconciler the periodic Redis/Postgres repair (M2.2b)
 */
@ConfigurationProperties("stampede.inventory.redis")
record RedisInventoryProperties(@DefaultValue("stampede") String keyPrefix,
                                @DefaultValue Consumer consumer,
                                @DefaultValue Reconciler reconciler) {

    // No ':' (the separator inside keys) and no glob characters (cleanup scans by prefix).
    private static final Pattern PREFIX = Pattern.compile("[a-z][a-z0-9_]{0,62}");

    RedisInventoryProperties {
        if (keyPrefix == null || !PREFIX.matcher(keyPrefix).matches()) {
            throw new IllegalArgumentException("stampede.inventory.redis.key-prefix must be lowercase letters, "
                    + "digits and '_' and start with a letter, was '" + keyPrefix + "'");
        }
    }

    /** For tests and slices that only need a prefix. */
    RedisInventoryProperties(String keyPrefix) {
        this(keyPrefix, new Consumer(true, "", Duration.ofSeconds(1), 100, Duration.ofSeconds(30), 5),
                new Reconciler(true, Duration.ofSeconds(30), Duration.ofSeconds(5)));
    }

    /**
     * @param enabled       run the consumer thread (off in tests, which drive it directly)
     * @param name          this instance's consumer name in the group; empty = the host name. Must be
     *                      unique per running instance (D-022, decision 1a)
     * @param block         how long one read waits for new entries; must stay below the Redis command
     *                      timeout ({@code spring.data.redis.timeout}, 2 s)
     * @param batchSize     entries per read
     * @param claimMinIdle  an entry delivered this long ago and still not acknowledged is taken over
     *                      (its consumer crashed, or applying it failed)
     * @param maxDeliveries after this many deliveries, a non-transient failure moves the entry to the
     *                      dead-letter stream (decision 2a)
     */
    record Consumer(@DefaultValue("true") boolean enabled,
                    @DefaultValue("") String name,
                    @DefaultValue("1s") Duration block,
                    @DefaultValue("100") int batchSize,
                    @DefaultValue("30s") Duration claimMinIdle,
                    @DefaultValue("5") int maxDeliveries) {

        Consumer {
            if (block.isNegative() || block.isZero() || block.compareTo(Duration.ofMillis(1900)) > 0) {
                throw new IllegalArgumentException("stampede.inventory.redis.consumer.block must be between 1 ms "
                        + "and 1.9 s (below the 2 s Redis command timeout), was " + block);
            }
            if (batchSize < 1 || maxDeliveries < 1) {
                throw new IllegalArgumentException("consumer batch-size and max-deliveries must be positive");
            }
        }
    }

    /**
     * @param enabled  run the reconciler on a schedule (off in tests, which call it directly)
     * @param interval delay between the end of one pass and the start of the next (decision 3: 30 s)
     * @param minAge   a Redis hold younger than this is the consumer's job, not the reconciler's
     */
    record Reconciler(@DefaultValue("true") boolean enabled,
                      @DefaultValue("30s") Duration interval,
                      @DefaultValue("5s") Duration minAge) {
    }
}
