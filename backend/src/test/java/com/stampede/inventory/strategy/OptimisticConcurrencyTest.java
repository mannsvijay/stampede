package com.stampede.inventory.strategy;

import org.springframework.beans.factory.annotation.Autowired;

/**
 * M2.1: the concurrency harness ({@link StrategyConcurrencyTest}) against the optimistic strategy. A
 * CONTENDED rejection is a legal outcome (nothing written); every invariant, I7's zero exceptions
 * (so no 40P01 deadlocks) included, must hold exactly as for the pessimistic strategy.
 */
class OptimisticConcurrencyTest extends StrategyConcurrencyTest {

    @Autowired
    OptimisticInventoryStrategy optimistic;

    @Override
    protected InventoryStrategy strategyUnderTest() {
        return optimistic;
    }
}
