package com.stampede.inventory.strategy;

import org.springframework.beans.factory.annotation.Autowired;

/** The per-user cap under concurrency ({@link HoldCapConcurrencyTest}) against the pessimistic strategy. */
class PessimisticHoldCapConcurrencyTest extends HoldCapConcurrencyTest {

    @Autowired
    PessimisticInventoryStrategy pessimistic;

    @Override
    protected InventoryStrategy strategyUnderTest() {
        return pessimistic;
    }
}
