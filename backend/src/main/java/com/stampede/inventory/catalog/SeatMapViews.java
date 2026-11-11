package com.stampede.inventory.catalog;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Response bodies of GET /events/{id}/layout and GET /events/{id}/seatmap (M1.8, D-020).
 *
 * <p>The seat map comes in two parts so the large part is downloaded once: the <b>layout</b> (where
 * every seat is, what it costs; it never changes after seeding) and the <b>seat map</b> (which seats
 * are available right now, one bit per seat). They are joined by position: bit {@code i} of the seat
 * map describes {@code seats[i]} of the layout. Coordinates are venue units (docs/venue-format.md).
 */
final class SeatMapViews {

    private SeatMapViews() {
    }

    static final String SEAT_ORDER = "Seats are sorted by seat id as PostgreSQL orders uuid values (byte by "
            + "byte, the same as comparing the lowercase text form). A seat's position in this list is its "
            + "bit index in GET /events/{id}/seatmap.";

    static final String BITSET_FORMAT = "Base64 (RFC 4648 section 4, with padding) of ceil(seatCount / 8) "
            + "bytes. Bit i describes seats[i] of GET /events/{id}/layout: it is bit (7 - i % 8) of byte "
            + "floor(i / 8), i.e. the most significant bit first. 1 = AVAILABLE, 0 = held or sold. Unused "
            + "bits of the last byte are 0.";

    record LayoutRect(double x, double y, double width, double height) {}

    record LayoutPoint(double x, double y) {}

    record LayoutSection(
            String code,
            String name,
            @Schema(description = "Where the section's name is drawn when zoomed out") LayoutPoint label,
            @Schema(description = "Polygon of [x, y] points, drawn as a block when zoomed out")
            List<List<Double>> outline) {}

    record VenueLayout(
            UUID id,
            String name,
            int width,
            int height,
            @Schema(description = "Radius of each seat circle, in venue units") int seatRadius,
            @Schema(description = "Rectangle (top-left corner plus size) that shows which way the venue faces")
            LayoutRect stage,
            List<LayoutSection> sections) {}

    record LayoutSeat(
            UUID id,
            @Schema(description = "Section code", example = "FL-L") String section,
            @Schema(example = "A") String row,
            @Schema(example = "1") int number,
            @Schema(description = "Seat centre") double x,
            double y,
            @Schema(description = "The seat's price tier; prices are in GET /events/{id}") UUID tierId,
            boolean aisle,
            boolean accessible) {}

    record EventLayout(
            UUID eventId,
            VenueLayout venue,
            @Schema(description = SEAT_ORDER) List<LayoutSeat> seats) {}

    /**
     * @param asOf when the snapshot was read (the server's clock, so it also corrects the client's
     *             clock). M5.1 adds a sequence number for the realtime diffs; until then there is none.
     */
    record SeatMap(
            UUID eventId,
            @Schema(description = "Number of seats, equal to the length of the layout's seat list")
            int seatCount,
            @Schema(description = BITSET_FORMAT) String available,
            @Schema(description = "When this snapshot was read, by the server's clock") Instant asOf) {}
}
