package com.stampede.inventory.strategy;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Every {@link InventoryStrategy} bean, by kind. The hold service and the expiry sweeper use it to
 * pick the strategy named in {@code events.strategy}.
 */
@Component
public class InventoryStrategies {

    private final Map<StrategyKind, InventoryStrategy> byKind = new EnumMap<>(StrategyKind.class);

    InventoryStrategies(List<InventoryStrategy> strategies) {
        for (InventoryStrategy strategy : strategies) {
            if (byKind.put(strategy.kind(), strategy) != null) {
                throw new IllegalStateException("Two strategies for " + strategy.kind());
            }
        }
    }

    /**
     * The strategy for {@code events.strategy}. An event naming a strategy that isn't built yet
     * (REDIS_FIRST until M2.2) is a configuration bug, so this throws.
     */
    public InventoryStrategy forKind(String eventStrategy) {
        StrategyKind kind = StrategyKind.valueOf(eventStrategy);
        InventoryStrategy strategy = byKind.get(kind);
        if (strategy == null) {
            throw new IllegalStateException("No inventory strategy " + kind + " is implemented yet");
        }
        return strategy;
    }
}
