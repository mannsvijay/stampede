/**
 * Waiting room: Queue join, proof-of-work checks, admission scheduling and signed admission tokens.
 * Owns the Redis queue keys; no Postgres tables yet.
 * <p>
 * Allowed dependencies are the D-007 table in docs/DECISIONS.md; ModularityTest enforces them.
 */
@ApplicationModule(
        displayName = "Waiting room",
        allowedDependencies = {
                "identity :: api"
        })
package com.stampede.waitingroom;

import org.springframework.modulith.ApplicationModule;
