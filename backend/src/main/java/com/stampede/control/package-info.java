/**
 * Control: Admin APIs, the control room (metrics, queue depth, pause), chaos hooks and the invariant checker.
 * Owns tables: audit_log.
 * <p>
 * Allowed dependencies are the D-007 table in docs/DECISIONS.md; ModularityTest enforces them.
 */
@ApplicationModule(
        displayName = "Control",
        allowedDependencies = {
                "waitingroom :: api",
                "inventory :: api",
                "booking :: api",
                "payments :: api",
                "ticketing :: api",
                "waitingroom :: events",
                "inventory :: events",
                "booking :: events",
                "payments :: events",
                "ticketing :: events"
        })
package com.stampede.control;

import org.springframework.modulith.ApplicationModule;
