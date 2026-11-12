package com.stampede.inventory.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** The result of {@link HoldService#hold}. Every case except {@link Created} and {@link Existing} changed nothing. */
public sealed interface HoldResult {

    /** An ACTIVE hold as the buyer sees it. */
    record HoldView(UUID holdId, UUID eventId, List<UUID> seatIds, Instant expiresAt) {
        public HoldView {
            seatIds = List.copyOf(seatIds);
        }
    }

    /** A new hold now owns every requested seat. */
    record Created(HoldView hold) implements HoldResult {
    }

    /**
     * The caller already has an ACTIVE hold on exactly these seats, typically a client retrying after
     * a timeout whose first attempt succeeded (D-016). That hold is returned unchanged.
     */
    record Existing(HoldView hold) implements HoldResult {
    }

    /** No such event, or it is a DRAFT (indistinguishable, as in GET /events/{id}). */
    record EventNotFound() implements HoldResult {
    }

    /** The event's drop hasn't happened yet, or the event isn't SCHEDULED/LIVE. */
    record SaleNotOpen() implements HoldResult {
    }

    /** These seat ids are not seats of this event. */
    record UnknownSeats(List<UUID> seatIds) implements HoldResult {
        public UnknownSeats {
            seatIds = List.copyOf(seatIds);
        }
    }

    /** These seats are HELD or SOLD; the most recent was taken {@code takenAgoMs} ago. */
    record SeatTaken(List<UUID> seatIds, long takenAgoMs) implements HoldResult {
        public SeatTaken {
            seatIds = List.copyOf(seatIds);
        }
    }

    /**
     * Other buyers kept changing these seats while this request tried to hold them (optimistic
     * strategy, D-021); nothing changed, and trying again shortly may work.
     */
    record Contended(List<UUID> seatIds) implements HoldResult {
        public Contended {
            seatIds = List.copyOf(seatIds);
        }
    }

    /** Holding these seats would take the user over the event's per-user limit. */
    record LimitExceeded(List<UUID> seatIds, int limit) implements HoldResult {
        public LimitExceeded {
            seatIds = List.copyOf(seatIds);
        }
    }
}
