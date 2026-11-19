package com.stampede.shared.ordering;

import java.util.Comparator;
import java.util.UUID;

/**
 * UUIDs in the order Postgres sorts them, for Java code that must touch rows in the same order as SQL
 * that says {@code ORDER BY <uuid column>} (D-015's lock order).
 * <p>
 * Why not {@link UUID#compareTo}: it compares the two 64-bit halves as <b>signed</b> longs, so a UUID
 * whose first hex digit is 8 to f (sign bit set) sorts before one starting 0 to 7. Postgres compares
 * the 16 bytes unsigned, left to right, and puts it after. Comparing each half with
 * {@link Long#compareUnsigned} is exactly that byte comparison: the high half holds bytes 0 to 7
 * big-endian, the low half bytes 8 to 15.
 * <p>
 * M2.4a found a deadlock caused by the difference: ticket issuing inserted in Java order while the
 * holds locked the same seats in Postgres order.
 */
public final class PostgresUuidOrder {

    /** Postgres {@code uuid} order: unsigned, byte by byte. */
    public static final Comparator<UUID> COMPARATOR = Comparator
            .comparing(UUID::getMostSignificantBits, Long::compareUnsigned)
            .thenComparing(UUID::getLeastSignificantBits, Long::compareUnsigned);

    private PostgresUuidOrder() {
    }
}
