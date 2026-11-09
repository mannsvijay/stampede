package com.stampede.inventory.strategy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** The result of {@link InventoryStrategy#release}. */
public sealed interface ReleaseOutcome {

    /** The hold is closed and every one of its seats is AVAILABLE again. */
    record Released(List<UUID> seatIds) implements ReleaseOutcome {
        public Released {
            seatIds = List.copyOf(seatIds);
        }
    }

    /** The hold exists but is no longer ACTIVE (already released, expired or converted); nothing changed. */
    record NotActive(String holdStatus) implements ReleaseOutcome {
    }

    /**
     * Release with reason EXPIRED, but the hold has not expired by the database clock (for example it
     * was extended after the sweeper picked it); nothing changed.
     */
    record NotExpired(Instant expiresAt) implements ReleaseOutcome {
    }

    /** No hold with this id for this event; nothing changed. */
    record NotFound() implements ReleaseOutcome {
    }
}
