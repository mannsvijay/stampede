package com.stampede.inventory.holds;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Runs the expiry sweeper on every instance, {@code stampede.holds.sweeper.interval} after the
 * previous sweep finished (fixed delay, so sweeps never overlap on one instance). Off when
 * {@code stampede.holds.sweeper.enabled=false}; tests call {@link HoldExpirySweeper#sweepOnce()}
 * directly instead.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "stampede.holds.sweeper.enabled", havingValue = "true", matchIfMissing = true)
class HoldSweeperSchedule {

    private final HoldExpirySweeper sweeper;

    HoldSweeperSchedule(HoldExpirySweeper sweeper) {
        this.sweeper = sweeper;
    }

    @Scheduled(fixedDelayString = "${stampede.holds.sweeper.interval:PT5S}",
            initialDelayString = "${stampede.holds.sweeper.interval:PT5S}")
    void sweep() {
        sweeper.sweepOnce();
    }
}
