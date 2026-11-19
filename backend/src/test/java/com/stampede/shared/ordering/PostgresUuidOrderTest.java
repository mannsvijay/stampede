package com.stampede.shared.ordering;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@link PostgresUuidOrder} against the rule Postgres applies to {@code uuid}: the 16 bytes compared
 * unsigned, left to right. Plain unit test; the lock-order tests (PostgresLockingContractTest,
 * TicketIssuingConcurrencyTest) check the same order against a real Postgres.
 */
class PostgresUuidOrderTest {

    @Test
    void theSignBitOfEitherHalfSortsHighNotLow() {
        UUID low = UUID.fromString("10000000-0000-4000-8000-000000000001");
        UUID highFirstHalf = UUID.fromString("90000000-0000-4000-8000-000000000002");
        UUID lowSecondHalf = UUID.fromString("10000000-0000-4000-0000-000000000001");

        // Java's own order disagrees on both pairs: that is the trap (D-015).
        assertThat(highFirstHalf.compareTo(low)).isNegative();
        assertThat(low.compareTo(lowSecondHalf)).isNegative();

        assertThat(PostgresUuidOrder.COMPARATOR.compare(low, highFirstHalf)).isNegative();
        assertThat(PostgresUuidOrder.COMPARATOR.compare(lowSecondHalf, low)).isNegative();
    }

    @Test
    void sortsTheExtremesByUnsignedValue() {
        List<UUID> expected = List.of(
                UUID.fromString("00000000-0000-0000-0000-000000000000"),
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                UUID.fromString("00000000-0000-0000-7fff-ffffffffffff"),
                UUID.fromString("00000000-0000-0000-8000-000000000000"),
                UUID.fromString("00000000-0000-0001-0000-000000000000"),
                UUID.fromString("7fffffff-ffff-ffff-ffff-ffffffffffff"),
                UUID.fromString("80000000-0000-0000-0000-000000000000"),
                UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff"));

        List<UUID> shuffled = new java.util.ArrayList<>(expected);
        java.util.Collections.shuffle(shuffled, new Random(42));

        assertThat(shuffled.stream().sorted(PostgresUuidOrder.COMPARATOR).toList()).isEqualTo(expected);
    }

    @Test
    void agreesWithAnUnsignedByteComparisonOnRandomIds() {
        Random random = new Random(20261119);
        for (int i = 0; i < 10_000; i++) {
            UUID a = new UUID(random.nextLong(), random.nextLong());
            UUID b = new UUID(random.nextLong(), random.nextLong());

            assertThat(Integer.signum(PostgresUuidOrder.COMPARATOR.compare(a, b)))
                    .as("%s vs %s", a, b)
                    .isEqualTo(Integer.signum(Arrays.compareUnsigned(bytes(a), bytes(b))));
        }
    }

    private static byte[] bytes(UUID id) {
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }
}
