/**
 * Identity: Registration, login, JWT access tokens, rotating refresh tokens and device fingerprints.
 * Owns tables: users, devices.
 * <p>
 * Allowed dependencies are the D-007 table in docs/DECISIONS.md; ModularityTest enforces them.
 */
@ApplicationModule(
        displayName = "Identity",
        allowedDependencies = {})
package com.stampede.identity;

import org.springframework.modulith.ApplicationModule;
