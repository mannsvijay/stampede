/**
 * Control: Admin APIs, the control room (metrics, queue depth, pause), chaos hooks and the invariant checker.
 * Owns tables: audit_log.
 * <p>
 * Reads, and never writes, every table of inventory, booking, payments and ticketing in
 * {@code control.invariants} (the invariant checker): the one exception to D-007's table ownership,
 * recorded in D-019. Its rules are joins across modules, so it runs them in a read-only transaction.
 * <p>
 * Allowed dependencies are the D-007 table in docs/DECISIONS.md (plus shared, D-009); ModularityTest
 * enforces them.
 */
@ApplicationModule(
        displayName = "Control",
        allowedDependencies = {
                "shared :: security",
                "shared :: error",
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
