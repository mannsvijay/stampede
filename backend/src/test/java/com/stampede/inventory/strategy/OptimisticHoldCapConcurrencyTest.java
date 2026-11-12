package com.stampede.inventory.strategy;

import org.springframework.beans.factory.annotation.Autowired;

/** The per-user cap under concurrency ({@link HoldCapConcurrencyTest}) against the optimistic strategy. */
class OptimisticHoldCapConcurrencyTest extends HoldCapConcurrencyTest {

    @Autowired
    OptimisticInventoryStrategy optimistic;

    @Override
    protected InventoryStrategy strategyUnderTest() {
        return optimistic;
    }
}
