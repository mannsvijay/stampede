/**
 * Payments: Payment provider adapter, signed webhook ingestion, reconciliation polling and chaos settings.
 * Gets order id and amount as arguments, so it never depends on booking.
 * Owns tables: payments.
 * <p>
 * Allowed dependencies are the D-007 table in docs/DECISIONS.md (plus shared, D-009); ModularityTest
 * enforces them.
 */
@ApplicationModule(
        displayName = "Payments",
        allowedDependencies = {
                "shared :: security",
                "shared :: error"
        })
package com.stampede.payments;

import org.springframework.modulith.ApplicationModule;
