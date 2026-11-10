package com.stampede.ticketing.api;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Issue one ticket per seat of a PAID order (saga step TicketsIssued, D-018). Booking supplies
 * everything as arguments, so ticketing never reads booking's or inventory's tables (D-007).
 *
 * @param holderUserId the buyer; the only holder information in the signed payload (D-018: user id only)
 * @param seats        the order's seats with their labels (a display snapshot, not signed)
 */
public record IssueTickets(UUID orderId, UUID eventId, UUID holderUserId, List<Seat> seats) {

    /** One sold seat and its label as the buyer sees it: "Section A, Row 3, Seat 5". */
    public record Seat(UUID seatId, String section, String row, int number) {
        public Seat {
            Objects.requireNonNull(seatId, "seatId");
            Objects.requireNonNull(section, "section");
            Objects.requireNonNull(row, "row");
            if (number < 1) {
                throw new IllegalArgumentException("seat number must be positive: " + number);
            }
        }
    }

    public IssueTickets {
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(holderUserId, "holderUserId");
        seats = List.copyOf(seats);
        if (seats.isEmpty()) {
            throw new IllegalArgumentException("An order has at least one seat");
        }
        if (new HashSet<>(seats.stream().map(Seat::seatId).toList()).size() != seats.size()) {
            throw new IllegalArgumentException("Duplicate seat ids: " + seats);
        }
    }
}
