package com.stampede.inventory.strategy;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Binds {@code stampede.inventory.optimistic.*} for the optimistic strategy (D-021) and
 * {@code stampede.inventory.redis.*} for the Redis-first one (D-022).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({OptimisticRetryProperties.class, RedisInventoryProperties.class})
class InventoryStrategyConfig {
}
