package com.stampede.inventory.strategy;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Runs the Redis reconciler on every instance (D-022, decision 3: every 30 s, fixed delay so passes
 * never overlap on one instance). The first pass runs right after startup, which is also when every
 * open REDIS_FIRST event is first loaded into Redis (rule R0). Off when
 * {@code stampede.inventory.redis.reconciler.enabled=false}; tests call
 * {@link RedisReconciler#reconcileOnce()} directly.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "stampede.inventory.redis.reconciler.enabled", havingValue = "true", matchIfMissing = true)
class RedisReconcilerSchedule {

    private final RedisReconciler reconciler;

    RedisReconcilerSchedule(RedisReconciler reconciler) {
        this.reconciler = reconciler;
    }

    @Scheduled(fixedDelayString = "${stampede.inventory.redis.reconciler.interval:PT30S}")
    void reconcile() {
        reconciler.reconcileOnce();
    }
}
