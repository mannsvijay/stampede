package com.stampede.control.invariants;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The checker while the app runs (PRD 6.10 "runs continuously", M1.7, D-019): checks every event that
 * is neither DRAFT nor ENDED, with a grace period for checkouts still running, logs each violation at
 * ERROR and publishes the current count per rule as the gauge {@code stampede.invariants.violations}
 * (tag {@code rule}). The control room (M5.5) will read the same checker.
 * <p>
 * The gauge is registered, but {@code /actuator/metrics} is not exposed yet (only health is); the
 * ERROR log is what an operator sees today.
 */
@Component
class InvariantMonitor {

    private static final Logger log = LoggerFactory.getLogger(InvariantMonitor.class);
    /** Details logged per run; the count per rule is always complete. */
    static final int DETAILS_LOGGED = 20;

    private final InvariantChecker checker;
    private final Duration grace;
    private final Map<String, AtomicInteger> current = new LinkedHashMap<>();

    InvariantMonitor(InvariantChecker checker, MeterRegistry meters,
                     @Value("${stampede.invariants.monitor.grace:PT30S}") Duration grace) {
        if (grace.isZero() || grace.isNegative()) {
            // Zero is strict: every checkout in flight would be reported as broken.
            throw new IllegalStateException("stampede.invariants.monitor.grace must be positive, was " + grace);
        }
        this.checker = checker;
        this.grace = grace;
        for (String rule : checker.ruleIds()) {
            AtomicInteger count = new AtomicInteger();
            current.put(rule, count);
            Gauge.builder("stampede.invariants.violations", count, AtomicInteger::get)
                    .description("Rows currently breaking this invariant (PRD 6.10), as of the last check")
                    .tag("rule", rule)
                    .register(meters);
        }
    }

    /** One check of every open event; returns what it found (tests use it directly). */
    List<Violation> checkOnce() {
        List<Violation> violations = checker.checkOpenEvents(grace);
        Map<String, Integer> byRule = new LinkedHashMap<>();
        current.keySet().forEach(rule -> byRule.put(rule, 0));
        violations.forEach(v -> byRule.merge(v.ruleId(), 1, Integer::sum));
        byRule.forEach((rule, count) -> current.get(rule).set(count));
        if (!violations.isEmpty()) {
            log.error("Invariant violations: {} (by rule {}), grace {}", violations.size(), byRule, grace);
            violations.stream().limit(DETAILS_LOGGED)
                    .forEach(v -> log.error("Invariant violation in event {}: {}", v.eventId(), v));
        }
        return violations;
    }

    /** The gauge's current value for one rule (tests). */
    int current(String rule) {
        return current.get(rule).get();
    }
}
