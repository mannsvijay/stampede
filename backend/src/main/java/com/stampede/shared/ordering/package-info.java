/**
 * Shared ordering: {@link com.stampede.shared.ordering.PostgresUuidOrder}, UUIDs in Postgres order, for
 * Java code that must touch rows in D-015's lock order (D-009 amendment, 2026-11-19).
 */
@NamedInterface("ordering")
package com.stampede.shared.ordering;

import org.springframework.modulith.NamedInterface;
