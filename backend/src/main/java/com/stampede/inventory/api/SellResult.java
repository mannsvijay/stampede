package com.stampede.inventory.api;

import java.util.List;
import java.util.UUID;

/** The result of {@link HoldService#sellForOrder}. */
public sealed interface SellResult {

    /** The hold's seats are SOLD to the order and the hold is CONVERTED. */
    record Sold(List<UUID> seatIds) implements SellResult {
        public Sold {
            seatIds = List.copyOf(seatIds);
        }
    }

    /**
     * The hold had already been CONVERTED. {@code orders.hold_id} is unique (D-017), so the only order
     * it can have been converted to is the caller's: a previous sell for the same order committed
     * (for example, the call threw after the commit).
     */
    record AlreadySold() implements SellResult {
    }

    /** The hold expired or was released before the sale; nothing changed (HOLD_EXPIRED). */
    record NoLongerHeld(String holdStatus) implements SellResult {
    }
}
