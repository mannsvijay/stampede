package com.stampede.inventory.strategy;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Which strategies the concurrency harness runs ({@link StrategyConcurrencyTest}, {@link HoldCapConcurrencyTest};
 * M2.3b). {@code -Dstampede.harness.strategies=PESSIMISTIC,REDIS_FIRST} picks some (comma-separated, any
 * case); unset or blank runs all three. concurrency-proof.yml uses it to keep one job per strategy.
 * An unknown name throws, so a typo fails the run instead of quietly running nothing.
 */
final class HarnessStrategies {

    static final String PROPERTY = "stampede.harness.strategies";

    private HarnessStrategies() {
    }

    /** The strategies picked by the system property, in {@link StrategyKind} order. */
    static List<StrategyKind> selected() {
        return parse(System.getProperty(PROPERTY));
    }

    static List<StrategyKind> parse(String value) {
        if (value == null || value.isBlank()) {
            return List.of(StrategyKind.values());
        }
        Set<StrategyKind> kinds = EnumSet.noneOf(StrategyKind.class);
        for (String name : value.split(",")) {
            String trimmed = name.trim().toUpperCase(Locale.ROOT);
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                kinds.add(StrategyKind.valueOf(trimmed));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("-D" + PROPERTY + ": unknown strategy '" + name.trim()
                        + "'; valid: " + Arrays.toString(StrategyKind.values()), e);
            }
        }
        if (kinds.isEmpty()) {
            throw new IllegalArgumentException("-D" + PROPERTY + " names no strategy: '" + value + "'");
        }
        return List.copyOf(kinds);
    }
}
