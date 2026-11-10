package com.stampede.inventory.strategy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** The result of {@link InventoryStrategy#sell}. Every case except {@link Sold} changed nothing. */
public sealed interface SellOutcome {

    /** Every seat of the hold is SOLD to the order, and the hold is CONVERTED. */
    record Sold(List<UUID> seatIds) implements SellOutcome {
        public Sold {
            seatIds = List.copyOf(seatIds);
        }
    }

    /**
     * The hold is still ACTIVE, but its {@code expires_at} has passed by the database clock: the expiry
     * sweeper just hasn't reached it yet. It must not be sold.
     */
    record HoldExpired(Instant expiresAt) implements SellOutcome {
    }

    /** The hold is no longer ACTIVE (RELEASED, EXPIRED or already CONVERTED). */
    record NotActive(String holdStatus) implements SellOutcome {
    }

    /** No hold with this id for this event. */
    record NotFound() implements SellOutcome {
    }
}
