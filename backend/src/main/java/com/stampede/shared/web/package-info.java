/**
 * Shared web plumbing (D-009, D-010): the one application-wide problem+json exception handler, the
 * problem+json {@code /error} controller, and the OpenAPI configuration that documents them.
 * <p>
 * Internal to shared (not a named interface): no module can reference these classes. Spring finds
 * them by component scanning, and they apply to every module's controllers.
 */
package com.stampede.shared.web;
