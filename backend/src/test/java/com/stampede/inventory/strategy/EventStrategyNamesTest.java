package com.stampede.inventory.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.stampede.inventory.api.EventStrategy;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * The public {@link EventStrategy} (inventory's api, used by the admin endpoint) and the internal
 * {@link StrategyKind} name the same strategies in the same order (M2.3a): the switch maps one to the
 * other by name, and both are the values of {@code ck_events_strategy}.
 */
class EventStrategyNamesTest {

    @Test
    void theApiAndTheInternalEnumNameTheSameStrategies() {
        assertThat(Arrays.stream(EventStrategy.values()).map(Enum::name))
                .containsExactlyElementsOf(Arrays.stream(StrategyKind.values()).map(Enum::name).toList());
    }
}
