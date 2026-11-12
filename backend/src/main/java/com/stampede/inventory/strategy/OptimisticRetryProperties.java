package com.stampede.inventory.strategy;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Retry policy of the optimistic strategy (D-021), {@code stampede.inventory.optimistic.*}.
 * <p>
 * Before try n (n >= 2) the strategy sleeps a random time between 0 and
 * {@code min(backoffMax, backoffBase * 2^(n-2))}: "exponential backoff with full jitter". The random
 * part spreads out buyers who lost the same race, so they don't all retry at the same instant and
 * collide again.
 *
 * @param maxAttempts tries per call, the first one included (at least 1)
 * @param backoffBase the cap of the first backoff
 * @param backoffMax  the largest cap any backoff can have
 */
@ConfigurationProperties("stampede.inventory.optimistic")
record OptimisticRetryProperties(
        @DefaultValue("3") int maxAttempts,
        @DefaultValue("5ms") Duration backoffBase,
        @DefaultValue("50ms") Duration backoffMax) {

    OptimisticRetryProperties {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("stampede.inventory.optimistic.max-attempts must be at least 1, was "
                    + maxAttempts);
        }
        if (backoffBase.isNegative() || backoffMax.isNegative()) {
            throw new IllegalArgumentException("stampede.inventory.optimistic backoff durations must not be negative");
        }
    }

    /** The largest sleep before try {@code attempt} (2, 3, ...), in milliseconds. */
    long backoffCapMillis(int attempt) {
        int doublings = Math.min(attempt - 2, 20);  // 2^20 is far past any sensible cap; avoids overflow
        long cap = backoffBase.toMillis() << doublings;
        return Math.min(cap, backoffMax.toMillis());
    }
}
