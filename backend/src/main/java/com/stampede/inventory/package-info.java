/**
 * Inventory: Venues, events and seat state: the pluggable locking strategies, holds and hold expiry.
 * Owns tables: venues, sections, seats, events, price_tiers, event_seats, holds, user_event_allowance.
 * The seed loader (inventory.seed) is internal to this module.
 * <p>
 * Allowed dependencies are the D-007 table in docs/DECISIONS.md; ModularityTest enforces them.
 */
@ApplicationModule(
        displayName = "Inventory",
        allowedDependencies = {
                "waitingroom :: api"
        })
package com.stampede.inventory;

import org.springframework.modulith.ApplicationModule;
