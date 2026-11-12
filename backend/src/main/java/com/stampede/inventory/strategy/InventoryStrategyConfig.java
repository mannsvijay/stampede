package com.stampede.inventory.strategy;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Binds {@code stampede.inventory.optimistic.*} for the optimistic strategy (D-021). */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OptimisticRetryProperties.class)
class InventoryStrategyConfig {
}
