package com.stampede.inventory.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The result of {@link HoldService#checkoutHold}: can this hold become an order right now? An
 * unlocked read; {@link HoldService#sellForOrder} decides again under the hold lock.
 */
public sealed interface CheckoutHold {

    /** One held seat with the price of its tier, in paise. */
    record SeatPrice(UUID seatId, long pricePaise) {
    }

    /** The caller's hold is ACTIVE and not expired by the database clock. */
    record Orderable(UUID holdId, UUID eventId, List<SeatPrice> seats, Instant expiresAt) implements CheckoutHold {
        public Orderable {
            seats = List.copyOf(seats);
        }

        public long totalPaise() {
            return seats.stream().mapToLong(SeatPrice::pricePaise).sum();
        }
    }

    /** The hold was released, expired, or is still ACTIVE but past its expiry (not swept yet). */
    record Expired() implements CheckoutHold {
    }

    /** The hold has already become an order. */
    record Converted() implements CheckoutHold {
    }

    /** No hold with this id belongs to the caller (unknown, or someone else's; indistinguishable). */
    record NotFound() implements CheckoutHold {
    }
}
