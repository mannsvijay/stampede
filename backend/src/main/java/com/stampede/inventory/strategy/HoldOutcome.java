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
     * so the caller can tell the buyer which seats to pick again.
     */
    record Rejected(HoldRejection reason, List<UUID> seatIds) implements HoldOutcome {
        public Rejected {
            seatIds = List.copyOf(seatIds);
        }
    }
}
