/**
 * Realtime: WebSocket and SSE fan-out of seat, queue and saga updates. Only reacts to events; calls no
 * other module. Owns no tables.
 * <p>
 * Allowed dependencies are the D-007 table in docs/DECISIONS.md; ModularityTest enforces them.
 */
@ApplicationModule(
        displayName = "Realtime",
        allowedDependencies = {
                "inventory :: events",
                "waitingroom :: events",
                "booking :: events"
        })
package com.stampede.realtime;

import org.springframework.modulith.ApplicationModule;
