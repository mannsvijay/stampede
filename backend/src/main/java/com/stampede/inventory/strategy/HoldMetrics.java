package com.stampede.inventory.strategy;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * Counters and a timer for {@link InventoryStrategy#hold}, so the M2.5 benchmark can compare the
 * strategies (D-021). Tags are only {@code strategy} and {@code outcome}, both small fixed sets: never
 * a seat, hold, user or event id, which would create one time series per value.
 * <ul>
 *   <li>{@code stampede.inventory.hold.attempts{strategy}}: transactions started. Pessimistic: one per
 *       call. Optimistic: one per try, so attempts / calls is the average number of tries.</li>
 *   <li>{@code stampede.inventory.hold.conflicts{strategy}}: tries that lost a version check and were
 *       rolled back (always 0 for pessimistic, whose losers wait for the lock instead).</li>
 *   <li>{@code stampede.inventory.hold.duration{strategy,outcome}}: whole calls, retries and backoff
 *       included; its count per outcome is the number of calls that ended that way.</li>
 * </ul>
 */
@Component
class HoldMetrics {

    static final String ATTEMPTS = "stampede.inventory.hold.attempts";
    static final String CONFLICTS = "stampede.inventory.hold.conflicts";
    static final String DURATION = "stampede.inventory.hold.duration";

    private final MeterRegistry registry;

    HoldMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    void attempt(StrategyKind strategy) {
        Counter.builder(ATTEMPTS)
                .description("Hold transactions started (one per try)")
                .tag("strategy", strategy.name())
                .register(registry)
                .increment();
    }

    void conflict(StrategyKind strategy) {
        Counter.builder(CONFLICTS)
                .description("Hold tries that lost a version check and were rolled back")
                .tag("strategy", strategy.name())
                .register(registry)
                .increment();
    }

    /** @param outcome null when the call threw */
    void finished(StrategyKind strategy, HoldOutcome outcome, long nanos) {
        Timer.builder(DURATION)
                .description("Hold calls, retries and backoff included")
                .tag("strategy", strategy.name())
                .tag("outcome", label(outcome))
                .register(registry)
                .record(nanos, TimeUnit.NANOSECONDS);
    }

    /** held, unknown_seat, seat_taken, limit_exceeded, contended, or error. */
    static String label(HoldOutcome outcome) {
        return switch (outcome) {
            case null -> "error";
            case HoldOutcome.Held held -> "held";
            case HoldOutcome.Rejected rejected -> rejected.reason().name().toLowerCase(Locale.ROOT);
        };
    }
}
