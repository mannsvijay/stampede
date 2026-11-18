package com.stampede.inventory.strategy;

import static com.stampede.inventory.strategy.StrategyKind.OPTIMISTIC;
import static com.stampede.inventory.strategy.StrategyKind.PESSIMISTIC;
import static com.stampede.inventory.strategy.StrategyKind.REDIS_FIRST;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** The harness's strategy filter, {@code -Dstampede.harness.strategies} (M2.3b). */
class HarnessStrategiesTest {

    @Test
    void unsetOrBlankRunsAllThree() {
        assertThat(HarnessStrategies.parse(null)).containsExactly(PESSIMISTIC, OPTIMISTIC, REDIS_FIRST);
        assertThat(HarnessStrategies.parse("  ")).containsExactly(PESSIMISTIC, OPTIMISTIC, REDIS_FIRST);
    }

    @Test
    void namesAreCommaSeparatedAnyCaseInEnumOrderWithoutDuplicates() {
        assertThat(HarnessStrategies.parse("redis_first, Pessimistic,PESSIMISTIC"))
                .containsExactly(PESSIMISTIC, REDIS_FIRST);
    }

    @Test
    void anUnknownNameFails() {
        assertThatThrownBy(() -> HarnessStrategies.parse("PESSIMISTIC,REDIS"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown strategy 'REDIS'");
    }

    @Test
    void onlyCommasFails() {
        assertThatThrownBy(() -> HarnessStrategies.parse(" , "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("names no strategy");
    }
}
