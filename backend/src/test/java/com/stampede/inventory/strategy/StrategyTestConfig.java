package com.stampede.inventory.strategy;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/**
 * The inventory strategy beans for a {@code @JdbcTest} slice, which doesn't scan components or set up
 * metrics: both Postgres strategies, what they share, the retry properties, and an in-memory meter
 * registry the tests can read counters from.
 */
@TestConfiguration(proxyBeanMethods = false)
@Import({SeatRows.class, HoldClosing.class, HoldMetrics.class, PessimisticInventoryStrategy.class,
        OptimisticInventoryStrategy.class, InventoryStrategyConfig.class})
class StrategyTestConfig {

    @Bean
    MeterRegistry meterRegistry() {
        return new SimpleMeterRegistry();
    }
}
