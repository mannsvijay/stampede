package com.stampede.inventory.strategy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** The result of {@link InventoryStrategy#hold}. */
public sealed interface HoldOutcome {

    /**
     * Every requested seat is now HELD by {@code holdId}.
     *
     * @param expiresAt when the hold expires, by the strategy's own clock (stored in
     *                  {@code holds.expires_at})
     */
    record Held(UUID holdId, List<UUID> seatIds, Instant expiresAt) implements HoldOutcome {
        public Held {
            seatIds = List.copyOf(seatIds);
        }
    }

    /**
     * Nothing changed. {@code seatIds} are the seats that caused the rejection (not the whole request),
     * so the caller can tell the buyer which seats to pick again. For LIMIT_EXCEEDED that is every
     * requested seat; for CONTENDED, the seat the last try lost its version check on.
     *
     * @param takenAgoMs SEAT_TAKEN only: how long ago the most recently taken of {@code seatIds} was
     *                   taken, by the database clock (from its owner's {@code created_at}, D-016);
     *                   null for the other reasons
     */
    record Rejected(HoldRejection reason, List<UUID> seatIds, Long takenAgoMs) implements HoldOutcome {
        public Rejected {
            seatIds = List.copyOf(seatIds);
            if ((takenAgoMs != null) != (reason == HoldRejection.SEAT_TAKEN)) {
                throw new IllegalArgumentException("takenAgoMs is set exactly for SEAT_TAKEN, was " + takenAgoMs
                        + " for " + reason);
            }
        }

        /** A rejection without {@code takenAgoMs} (UNKNOWN_SEAT, LIMIT_EXCEEDED). */
        public Rejected(HoldRejection reason, List<UUID> seatIds) {
            this(reason, seatIds, null);
        }
    }
}
