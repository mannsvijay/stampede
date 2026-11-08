/**
 * Ticketing: Ticket issuing and signing, transfers and check-ins.
 * Owns tables: tickets, checkins.
 * <p>
 * Allowed dependencies are the D-007 table in docs/DECISIONS.md (plus shared, D-009); ModularityTest
 * enforces them.
 */
@ApplicationModule(
        displayName = "Ticketing",
        allowedDependencies = {
                "shared :: security",
                "shared :: error",
                "identity :: api"
        })
package com.stampede.ticketing;

import org.springframework.modulith.ApplicationModule;
