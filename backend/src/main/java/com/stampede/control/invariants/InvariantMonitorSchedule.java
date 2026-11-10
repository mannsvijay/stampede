package com.stampede.control.invariants;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Runs the invariant monitor on every instance, {@code stampede.invariants.monitor.interval} after the
 * previous check finished (fixed delay, so checks never overlap on one instance; read-only, so
 * several instances checking at once is harmless). Off when
 * {@code stampede.invariants.monitor.enabled=false}, as in tests, which call
 * {@link InvariantMonitor#checkOnce()} directly.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "stampede.invariants.monitor.enabled", havingValue = "true", matchIfMissing = true)
class InvariantMonitorSchedule {

    private final InvariantMonitor monitor;

    InvariantMonitorSchedule(InvariantMonitor monitor) {
        this.monitor = monitor;
    }

    @Scheduled(fixedDelayString = "${stampede.invariants.monitor.interval:PT30S}",
            initialDelayString = "${stampede.invariants.monitor.interval:PT30S}")
    void check() {
        monitor.checkOnce();
    }
}
