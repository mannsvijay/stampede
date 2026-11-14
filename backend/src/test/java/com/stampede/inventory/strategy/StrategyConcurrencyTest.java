package com.stampede.inventory.strategy;

import com.stampede.testsupport.TestDatabases;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The concurrency harness ({@link StrategyRaceTest}) for the two Postgres strategies,
 * {@link PessimisticConcurrencyTest} and {@link OptimisticConcurrencyTest}: they share this context and
 * its one database. The Redis-first strategy has its own ({@link RedisFirstConcurrencyTest}).
 */
abstract class StrategyConcurrencyTest extends StrategyRaceTest {

    /** One database for both subclasses (they share this context), on the shared test Postgres. */
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabases.register(registry, "strategy_concurrency");
    }
}
