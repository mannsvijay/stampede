package com.stampede.inventory.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The result of {@link HoldService#checkoutHold}: can this hold become an order right now? An
 * unlocked read; {@link HoldService#sellForOrder} decides again under the hold lock.
 */
public sealed interface CheckoutHold {

    /**
     * One seat of the hold: its tier price in paise, and its human label (section code, row, seat
     * number) so booking can hand it to ticketing without ticketing reading inventory's tables (D-018).
     */
    record CheckoutSeat(UUID seatId, long pricePaise, String section, String row, int number) {
    }

    /** The caller's hold is ACTIVE and not expired by the database clock. */
    record Orderable(UUID holdId, UUID eventId, List<CheckoutSeat> seats, Instant expiresAt) implements CheckoutHold {
        public Orderable {
            seats = List.copyOf(seats);
        }

        public long totalPaise() {
            return seats.stream().mapToLong(CheckoutSeat::pricePaise).sum();
        }
    }

    /** The hold was released, expired, or is still ACTIVE but past its expiry (not swept yet). */
    record Expired() implements CheckoutHold {
    }

    /**
     * The hold has already become an order. Its seats are exactly that order's seats (a hold converts
     * only by selling all of them), so a retried checkout can finish issuing tickets (D-018).
     */
    record Converted(UUID eventId, List<CheckoutSeat> seats) implements CheckoutHold {
        public Converted {
            seats = List.copyOf(seats);
        }
    }

    /** No hold with this id belongs to the caller (unknown, or someone else's; indistinguishable). */
    record NotFound() implements CheckoutHold {
    }
}
