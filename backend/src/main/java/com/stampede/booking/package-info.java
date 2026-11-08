/**
 * Booking: Orders, idempotency keys and the checkout saga orchestrator. The orchestrator calls inventory,
 * payments and ticketing directly and reacts to their events; nothing depends on booking except
 * realtime and control.
 * Owns tables: orders, order_items, idempotency_keys.
 * <p>
 * Allowed dependencies are the D-007 table in docs/DECISIONS.md (plus shared, D-009); ModularityTest
 * enforces them.
 */
@ApplicationModule(
        displayName = "Booking",
        allowedDependencies = {
                "shared :: security",
                "shared :: error",
                "inventory :: api",
                "inventory :: events",
                "payments :: api",
                "payments :: events",
                "ticketing :: api"
        })
package com.stampede.booking;

import org.springframework.modulith.ApplicationModule;
