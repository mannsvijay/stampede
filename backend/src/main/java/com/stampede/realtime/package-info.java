/**
 * Realtime: WebSocket and SSE fan-out of seat, queue and saga updates. Only reacts to events; calls no
 * other module. Owns no tables.
 * <p>
 * Allowed dependencies are the D-007 table in docs/DECISIONS.md (plus shared, D-009); ModularityTest
 * enforces them.
 */
@ApplicationModule(
        displayName = "Realtime",
        allowedDependencies = {
                "shared :: security",
                "shared :: error",
                "inventory :: events",
                "waitingroom :: events",
                "booking :: events"
        })
package com.stampede.realtime;

import org.springframework.modulith.ApplicationModule;
