package com.stampede.inventory.seed;

import java.util.List;

/**
 * A venue layout file (format described in docs/venue-format.md).
 *
 * <p>Fields are boxed types on purpose: a field missing from the JSON becomes {@code null}, which
 * {@link SeedValidator} reports, instead of silently becoming 0 or false.
 */
public record VenueFile(
        Integer formatVersion,
        String slug,
        String name,
        Integer width,
        Integer height,
        Integer seatRadius,
        Rect stage,
        List<Section> sections) {

    public record Rect(Double x, Double y, Double width, Double height) {}

    public record Point(Double x, Double y) {}

    /** {@code outline} is a polygon: a list of [x, y] pairs. */
    public record Section(
            String code,
            String name,
            String defaultTier,
            Point label,
            List<List<Double>> outline,
            List<Row> rows) {}

    public record Row(String label, List<Seat> seats) {}

    /** {@code aisle} and {@code accessible} are optional; missing means false. */
    public record Seat(Integer number, Double x, Double y, Boolean aisle, Boolean accessible) {

        public boolean isAisle() {
            return Boolean.TRUE.equals(aisle);
        }

        public boolean isAccessible() {
            return Boolean.TRUE.equals(accessible);
        }
    }

    public int seatCount() {
        return sections.stream()
                .flatMap(s -> s.rows().stream())
                .mapToInt(r -> r.seats().size())
                .sum();
    }
}
