package com.stampede.inventory.seed;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Generates the demo venue file: an arena of about 2,000 seats.
 *
 * <p>Run with {@code cd backend && ./gradlew generateVenue}. It writes
 * {@code src/main/resources/seed/venues/demo-arena.json}, which is committed;
 * {@code VenueGeneratorTest} fails if the committed file and this code drift apart.
 *
 * <p>This is a dev tool, so it lives in the test source set and never ships in the application jar.
 *
 * <p>Deterministic: no randomness, a fixed output order, {@link StrictMath} for trigonometry
 * ({@code Math.sin} may use CPU-specific intrinsics; {@code StrictMath} gives bit-identical results on
 * every JVM), and every coordinate rounded to 0.1.
 *
 * <p>Layout (venue units, origin top-left, y pointing down, matching Canvas2D):
 * <ul>
 *   <li>Stage at the top centre.</li>
 *   <li>Floor: sections FL-L and FL-R, straight rows with a centre aisle between them (PLATINUM).</li>
 *   <li>Lower bowl: sections 101-105 on concentric arcs around the floor (GOLD). The back row's four
 *       end seats on each side are wheelchair-accessible.</li>
 *   <li>Upper tier: sections 201-203 on wider arcs behind the bowl (SILVER).</li>
 * </ul>
 * Rows are labelled A, B, C... from the front. Seat 1 is at the left end of each row as drawn
 * (smallest x on the floor, the counter-clockwise end on arcs). The first and last seat of every row
 * are aisle seats, because sections are separated by aisles.
 */
public final class VenueGenerator {

    static final String OUTPUT = "src/main/resources/seed/venues/demo-arena.json";

    private static final int WIDTH = 1200;
    private static final int HEIGHT = 700;
    private static final int SEAT_RADIUS = 4;

    // Floor: 2 sections x 10 rows x 20 seats.
    private static final int FLOOR_ROWS = 10;
    private static final int FLOOR_SEATS_PER_ROW = 20;
    private static final double FLOOR_PITCH = 10.0;       // distance between seat centres
    private static final double FLOOR_ROW_SPACING = 12.0;
    private static final double FLOOR_FIRST_ROW_Y = 130.0;

    // Arcs are centred on this point, just below the stage.
    private static final double CENTRE_X = 600.0;
    private static final double CENTRE_Y = 120.0;
    private static final double ARC_PITCH = 9.0;          // target distance between seats along a row

    private static final double OUTLINE_PADDING = 6.0;

    private VenueGenerator() {}

    public static void main(String[] args) throws IOException {
        Path out = Path.of(args.length > 0 ? args[0] : OUTPUT);
        VenueFile venue = generate();
        Files.createDirectories(out.toAbsolutePath().getParent());
        Files.writeString(out, toJson(venue), StandardCharsets.UTF_8);
        System.out.println("Wrote " + out.toAbsolutePath());
        for (VenueFile.Section s : venue.sections()) {
            int seats = s.rows().stream().mapToInt(r -> r.seats().size()).sum();
            System.out.printf("  section %-5s %-9s %2d rows %4d seats%n", s.code(), s.defaultTier(), s.rows().size(), seats);
        }
        long aisle = allSeats(venue).stream().filter(VenueFile.Seat::isAisle).count();
        long accessible = allSeats(venue).stream().filter(VenueFile.Seat::isAccessible).count();
        System.out.println("  total " + venue.seatCount() + " seats (" + aisle + " aisle, " + accessible + " accessible)");
    }

    static VenueFile generate() {
        List<VenueFile.Section> sections = new ArrayList<>();

        sections.add(floorSection("FL-L", "Floor Left", 390.0));
        sections.add(floorSection("FL-R", "Floor Right", 620.0));

        // Lower bowl: 5 sections across 170..10 degrees, 4-degree aisles between them.
        double bowlGap = 4.0;
        double bowlSpan = (160.0 - 4 * bowlGap) / 5;
        for (int k = 0; k < 5; k++) {
            double start = 170.0 - k * (bowlSpan + bowlGap);
            sections.add(arcSection(String.valueOf(101 + k), "Lower " + (101 + k), "GOLD",
                    start, start - bowlSpan, 260.0, 12, 13.0, true));
        }

        // Upper tier: 3 sections across 125..55 degrees, 3-degree aisles between them.
        double upperGap = 3.0;
        double upperSpan = (70.0 - 2 * upperGap) / 3;
        for (int k = 0; k < 3; k++) {
            double start = 125.0 - k * (upperSpan + upperGap);
            sections.add(arcSection(String.valueOf(201 + k), "Upper " + (201 + k), "SILVER",
                    start, start - upperSpan, 440.0, 9, 12.0, false));
        }

        return new VenueFile(1, "demo-arena", "Stampede Demo Arena", WIDTH, HEIGHT, SEAT_RADIUS,
                new VenueFile.Rect(450.0, 30.0, 300.0, 50.0), sections);
    }

    /** A rectangular block of straight rows; {@code firstSeatX} is the x of seat 1. */
    private static VenueFile.Section floorSection(String code, String name, double firstSeatX) {
        List<VenueFile.Row> rows = new ArrayList<>();
        for (int r = 0; r < FLOOR_ROWS; r++) {
            double y = FLOOR_FIRST_ROW_Y + r * FLOOR_ROW_SPACING;
            List<VenueFile.Seat> seats = new ArrayList<>();
            for (int i = 0; i < FLOOR_SEATS_PER_ROW; i++) {
                boolean aisle = i == 0 || i == FLOOR_SEATS_PER_ROW - 1;
                seats.add(seat(i + 1, firstSeatX + i * FLOOR_PITCH, y, aisle, false));
            }
            rows.add(new VenueFile.Row(rowLabel(r), seats));
        }
        double left = firstSeatX - OUTLINE_PADDING;
        double right = firstSeatX + (FLOOR_SEATS_PER_ROW - 1) * FLOOR_PITCH + OUTLINE_PADDING;
        double top = FLOOR_FIRST_ROW_Y - OUTLINE_PADDING;
        double bottom = FLOOR_FIRST_ROW_Y + (FLOOR_ROWS - 1) * FLOOR_ROW_SPACING + OUTLINE_PADDING;
        List<List<Double>> outline = List.of(
                point(left, top), point(right, top), point(right, bottom), point(left, bottom));
        VenueFile.Point label = new VenueFile.Point(round((left + right) / 2), round((top + bottom) / 2));
        return new VenueFile.Section(code, name, "PLATINUM", label, outline, rows);
    }

    /**
     * A section whose rows are arcs around (CENTRE_X, CENTRE_Y), from {@code startDeg} down to
     * {@code endDeg}. Angles are measured from the +x axis towards +y (down the screen), so 90 degrees is
     * straight below the centre. Each row fits as many seats as its arc length allows at ARC_PITCH.
     */
    private static VenueFile.Section arcSection(String code, String name, String tier, double startDeg,
                                                double endDeg, double innerRadius, int rowCount,
                                                double rowSpacing, boolean accessibleBackRow) {
        double spanRad = StrictMath.toRadians(startDeg - endDeg);
        List<VenueFile.Row> rows = new ArrayList<>();
        for (int r = 0; r < rowCount; r++) {
            double radius = innerRadius + r * rowSpacing;
            int count = (int) StrictMath.floor(radius * spanRad / ARC_PITCH);
            boolean backRow = r == rowCount - 1;
            List<VenueFile.Seat> seats = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                // Spread seats evenly, half a step in from each end of the arc.
                double angle = startDeg - (i + 0.5) * (startDeg - endDeg) / count;
                double rad = StrictMath.toRadians(angle);
                boolean aisle = i == 0 || i == count - 1;
                boolean accessible = accessibleBackRow && backRow && (i < 4 || i >= count - 4);
                seats.add(seat(i + 1, CENTRE_X + radius * StrictMath.cos(rad),
                        CENTRE_Y + radius * StrictMath.sin(rad), aisle, accessible));
            }
            rows.add(new VenueFile.Row(rowLabel(r), seats));
        }

        double inner = innerRadius - OUTLINE_PADDING;
        double outer = innerRadius + (rowCount - 1) * rowSpacing + OUTLINE_PADDING;
        List<List<Double>> outline = new ArrayList<>();
        int steps = 6;
        for (int s = 0; s <= steps; s++) {        // inner arc, start -> end
            outline.add(polar(inner, startDeg - s * (startDeg - endDeg) / steps));
        }
        for (int s = steps; s >= 0; s--) {        // outer arc, end -> start
            outline.add(polar(outer, startDeg - s * (startDeg - endDeg) / steps));
        }
        List<Double> mid = polar((inner + outer) / 2, (startDeg + endDeg) / 2);
        return new VenueFile.Section(code, name, tier, new VenueFile.Point(mid.get(0), mid.get(1)), outline, rows);
    }

    // ---------------------------------------------------------------------------------------------
    // Small helpers
    // ---------------------------------------------------------------------------------------------

    private static VenueFile.Seat seat(int number, double x, double y, boolean aisle, boolean accessible) {
        return new VenueFile.Seat(number, round(x), round(y), aisle ? Boolean.TRUE : null,
                accessible ? Boolean.TRUE : null);
    }

    private static List<Double> polar(double radius, double deg) {
        double rad = StrictMath.toRadians(deg);
        return point(CENTRE_X + radius * StrictMath.cos(rad), CENTRE_Y + radius * StrictMath.sin(rad));
    }

    private static List<Double> point(double x, double y) {
        return List.of(round(x), round(y));
    }

    private static String rowLabel(int index) {
        return String.valueOf((char) ('A' + index));
    }

    private static double round(double value) {
        return Math.round(value * 10) / 10.0;
    }

    private static List<VenueFile.Seat> allSeats(VenueFile venue) {
        return venue.sections().stream().flatMap(s -> s.rows().stream()).flatMap(r -> r.seats().stream()).toList();
    }

    // ---------------------------------------------------------------------------------------------
    // JSON writer: hand-written so the layout is stable and one seat sits on one line (readable diffs)
    // ---------------------------------------------------------------------------------------------

    static String toJson(VenueFile v) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"formatVersion\": ").append(v.formatVersion()).append(",\n");
        sb.append("  \"slug\": ").append(str(v.slug())).append(",\n");
        sb.append("  \"name\": ").append(str(v.name())).append(",\n");
        sb.append("  \"width\": ").append(v.width()).append(",\n");
        sb.append("  \"height\": ").append(v.height()).append(",\n");
        sb.append("  \"seatRadius\": ").append(v.seatRadius()).append(",\n");
        VenueFile.Rect st = v.stage();
        sb.append("  \"stage\": { \"x\": ").append(num(st.x())).append(", \"y\": ").append(num(st.y()))
                .append(", \"width\": ").append(num(st.width())).append(", \"height\": ").append(num(st.height()))
                .append(" },\n");
        sb.append("  \"sections\": [\n");
        for (int s = 0; s < v.sections().size(); s++) {
            VenueFile.Section sec = v.sections().get(s);
            sb.append("    {\n");
            sb.append("      \"code\": ").append(str(sec.code())).append(",\n");
            sb.append("      \"name\": ").append(str(sec.name())).append(",\n");
            sb.append("      \"defaultTier\": ").append(str(sec.defaultTier())).append(",\n");
            sb.append("      \"label\": { \"x\": ").append(num(sec.label().x())).append(", \"y\": ")
                    .append(num(sec.label().y())).append(" },\n");
            sb.append("      \"outline\": [");
            for (int p = 0; p < sec.outline().size(); p++) {
                List<Double> pt = sec.outline().get(p);
                sb.append(p == 0 ? "" : ", ").append('[').append(num(pt.get(0))).append(", ")
                        .append(num(pt.get(1))).append(']');
            }
            sb.append("],\n");
            sb.append("      \"rows\": [\n");
            for (int r = 0; r < sec.rows().size(); r++) {
                VenueFile.Row row = sec.rows().get(r);
                sb.append("        {\n");
                sb.append("          \"label\": ").append(str(row.label())).append(",\n");
                sb.append("          \"seats\": [\n");
                for (int i = 0; i < row.seats().size(); i++) {
                    VenueFile.Seat seat = row.seats().get(i);
                    sb.append("            { \"number\": ").append(seat.number())
                            .append(", \"x\": ").append(num(seat.x()))
                            .append(", \"y\": ").append(num(seat.y()));
                    if (seat.isAisle()) {
                        sb.append(", \"aisle\": true");
                    }
                    if (seat.isAccessible()) {
                        sb.append(", \"accessible\": true");
                    }
                    sb.append(" }").append(i < row.seats().size() - 1 ? "," : "").append('\n');
                }
                sb.append("          ]\n");
                sb.append("        }").append(r < sec.rows().size() - 1 ? "," : "").append('\n');
            }
            sb.append("      ]\n");
            sb.append("    }").append(s < v.sections().size() - 1 ? "," : "").append('\n');
        }
        sb.append("  ]\n");
        sb.append("}\n");
        return sb.toString();
    }

    /** Always one decimal place, e.g. 160.5 or 390.0; never scientific notation. */
    private static String num(double value) {
        return BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP).toPlainString();
    }

    /** Names here are plain ASCII, but escape quotes and backslashes anyway. */
    private static String str(String value) {
        return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }
}
