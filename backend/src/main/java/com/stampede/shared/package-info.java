/**
 * Shared: the few types every module needs, and nothing else (D-009).
 * <ul>
 *   <li>{@code shared.security}: {@link com.stampede.shared.security.CurrentUser}, the authenticated
 *       caller, so modules don't depend on identity just to know who is calling.</li>
 *   <li>{@code shared.error}: the stable error codes (PRD section 10) and problem+json helpers.</li>
 * </ul>
 * Shared depends on no other module.
 */
@ApplicationModule(
        displayName = "Shared",
        allowedDependencies = {})
package com.stampede.shared;

import org.springframework.modulith.ApplicationModule;
