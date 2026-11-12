package com.stampede.inventory.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The pessimistic strategy (M1.2, D-015) against the shared contract ({@link InventoryStrategyContractTest}).
 * Every test there runs here too; the lock tests in it prove the {@code SELECT ... FOR UPDATE} order.
 */
class PessimisticInventoryStrategyTest extends InventoryStrategyContractTest {

    @Autowired
    PessimisticInventoryStrategy pessimistic;

    @Override
    protected InventoryStrategy strategyUnderTest() {
        return pessimistic;
    }

    @Test
    void kindIsPessimistic() {
        assertThat(strategy.kind()).isEqualTo(StrategyKind.PESSIMISTIC);
    }
}
