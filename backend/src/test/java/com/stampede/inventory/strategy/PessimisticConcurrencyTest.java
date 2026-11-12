package com.stampede.inventory.strategy;

import org.springframework.beans.factory.annotation.Autowired;

/** M1.3: the concurrency harness ({@link StrategyConcurrencyTest}) against the pessimistic strategy. */
class PessimisticConcurrencyTest extends StrategyConcurrencyTest {

    @Autowired
    PessimisticInventoryStrategy pessimistic;

    @Override
    protected InventoryStrategy strategyUnderTest() {
        return pessimistic;
    }
}
