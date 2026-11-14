package com.stampede.inventory.strategy;

import com.stampede.testsupport.TestDatabases;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The per-user cap under concurrency ({@link HoldCapRaceTest}) for the two Postgres strategies,
 * {@link PessimisticHoldCapConcurrencyTest} and {@link OptimisticHoldCapConcurrencyTest}: they share this
 * context and its one database. The Redis-first strategy has its own ({@link RedisFirstHoldCapConcurrencyTest}).
 */
abstract class HoldCapConcurrencyTest extends HoldCapRaceTest {

    /** One database for both subclasses (they share this context), on the shared test Postgres. */
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabases.register(registry, "hold_cap_concurrency");
    }
}
