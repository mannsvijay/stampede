/**
 * Identity: Registration, login, JWT access tokens, rotating refresh tokens and device fingerprints.
 * Owns tables: users, devices, refresh_tokens.
 * <p>
 * Allowed dependencies are the D-007 table in docs/DECISIONS.md (plus shared, D-009); ModularityTest
 * enforces them.
 */
@ApplicationModule(
        displayName = "Identity",
        allowedDependencies = {
                "shared :: security",
                "shared :: error"
        })
package com.stampede.identity;

import org.springframework.modulith.ApplicationModule;
