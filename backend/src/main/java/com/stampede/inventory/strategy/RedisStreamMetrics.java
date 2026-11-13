package com.stampede.inventory.strategy;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * Metrics of the Redis-first persistence path (D-022, M2.2b). Tags are small fixed sets, never ids.
 * <ul>
 *   <li>{@code stampede.inventory.redis.stream.lag}: entries not yet acknowledged (pending + not yet
 *       delivered). The consumer refreshes it; a scrape never calls Redis.</li>
 *   <li>{@code stampede.inventory.redis.stream.oldest.age}: age in ms (Redis clock) of the oldest
 *       unacknowledged entry, 0 when there is none. Invariant 5's 2 s window (M2.2c) and the M2.5
 *       benchmark read this one.</li>
 *   <li>{@code stampede.inventory.redis.applied{type,result}}: entries applied to Postgres;
 *       result = applied, duplicate (already applied), stale (Redis no longer had the hold, written
 *       as already over) or conflict (Postgres won).</li>
 *   <li>{@code stampede.inventory.redis.deadletters}: entries moved to the dead-letter stream.</li>
 *   <li>{@code stampede.inventory.redis.reconciler.repairs{rule}}: repairs per reconciler rule.</li>
 * </ul>
 */
@Component
class RedisStreamMetrics {

    static final String LAG = "stampede.inventory.redis.stream.lag";
    static final String OLDEST_AGE = "stampede.inventory.redis.stream.oldest.age";
    static final String APPLIED = "stampede.inventory.redis.applied";
    static final String DEAD_LETTERS = "stampede.inventory.redis.deadletters";
    static final String REPAIRS = "stampede.inventory.redis.reconciler.repairs";

    private final MeterRegistry registry;
    private final AtomicLong lag = new AtomicLong();
    private final AtomicLong oldestAgeMs = new AtomicLong();

    RedisStreamMetrics(MeterRegistry registry) {
        this.registry = registry;
        Gauge.builder(LAG, lag, AtomicLong::get)
                .description("Inventory stream entries not yet applied to Postgres and acknowledged")
                .register(registry);
        Gauge.builder(OLDEST_AGE, oldestAgeMs, AtomicLong::get)
                .description("Age in ms of the oldest unacknowledged inventory stream entry")
                .baseUnit("milliseconds")
                .register(registry);
    }

    void lag(long entries, long oldestAgeMillis) {
        lag.set(entries);
        oldestAgeMs.set(oldestAgeMillis);
    }

    void applied(StreamEntry.Type type, RedisHoldWriter.Applied result) {
        Counter.builder(APPLIED)
                .tag("type", type.name().toLowerCase(Locale.ROOT))
                .tag("result", result.name().toLowerCase(Locale.ROOT))
                .register(registry)
                .increment();
    }

    void deadLetter() {
        Counter.builder(DEAD_LETTERS).register(registry).increment();
    }

    void repair(String rule) {
        Counter.builder(REPAIRS).tag("rule", rule).register(registry).increment();
    }
}
