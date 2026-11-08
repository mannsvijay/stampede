/**
 * Shared: the few types every module needs, and nothing else (D-009).
 * <ul>
 *   <li>{@code shared.security}: {@link com.stampede.shared.security.CurrentUser}, the authenticated
 *       caller, so modules don't depend on identity just to know who is calling.</li>
 *   <li>{@code shared.error}: the stable error codes (PRD section 10) and problem+json helpers.</li>
 *   <li>{@code shared.web} (internal, D-010): the application-wide problem+json handler, the
 *       {@code /error} controller and the OpenAPI error documentation. Not a named interface.</li>
 * </ul>
 * Shared depends on no other module.
 */
@ApplicationModule(
        displayName = "Shared",
        allowedDependencies = {})
package com.stampede.shared;

import org.springframework.modulith.ApplicationModule;
