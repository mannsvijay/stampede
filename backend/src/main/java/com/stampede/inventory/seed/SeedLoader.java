package com.stampede.inventory.seed;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Loads a venue file and its demo event into Postgres.
 *
 * <p>How it stays safe to run repeatedly:
 * <ol>
 *   <li>Validate both files before writing anything.</li>
 *   <li>Insert every row with a deterministic id ({@link SeedIds}) and {@code ON CONFLICT DO NOTHING},
 *       so rows that already exist are skipped.</li>
 *   <li>Read back what's in the database and require it to match the files exactly. If a file changed
 *       after it was loaded, throw: the whole transaction rolls back, including any new rows.</li>
 * </ol>
 * Loaded data is never updated, because event seats (and later holds, orders and tickets) point at it.
 */
@Component
public class SeedLoader {

    static final String RESET_HINT = "Loaded seed data is never modified, because event seats (and later holds, "
            + "orders and tickets) point at it. To reset local data: run `docker compose down -v` (this deletes "
            + "the Postgres and Redis volumes), then `docker compose up -d`, then run the seed profile again.";

    private final JdbcClient jdbc;
    private final JdbcTemplate jdbcTemplate;  // JdbcClient has no batch API

    public SeedLoader(JdbcClient jdbc, JdbcTemplate jdbcTemplate) {
        this.jdbc = jdbc;
        this.jdbcTemplate = jdbcTemplate;
    }

    public record Result(UUID venueId, UUID eventId, int seats, int seatsInserted, int eventSeatsInserted) {}

    @Transactional
    public Result load(VenueFile venue, EventSeedFile event) {
        SeedValidator.validate(venue, event);

        UUID venueId = SeedIds.venue(venue.slug());
        UUID eventId = SeedIds.event(event.slug());

        insertVenue(venueId, venue);
        insertSections(venueId, venue);
        int seatsInserted = insertSeats(venue);
        insertEvent(eventId, venueId, event);
        insertTiers(eventId, event);
        int eventSeatsInserted = insertEventSeats(eventId);

        verifyMatchesFiles(venueId, eventId, venue, event);
        return new Result(venueId, eventId, venue.seatCount(), seatsInserted, eventSeatsInserted);
    }

    // ---------------------------------------------------------------------------------------------
    // Inserts
    // ---------------------------------------------------------------------------------------------

    private void insertVenue(UUID venueId, VenueFile venue) {
        jdbc.sql("""
                INSERT INTO venues (id, name, layout_json) VALUES (?, ?, ?::jsonb)
                ON CONFLICT (id) DO NOTHING
                """).params(venueId, venue.name(), SeedFiles.layoutJson(venue)).update();
    }

    private void insertSections(UUID venueId, VenueFile venue) {
        List<Object[]> rows = venue.sections().stream()
                .map(s -> new Object[] {SeedIds.section(venue.slug(), s.code()), venueId, s.code(), s.defaultTier()})
                .toList();
        jdbcTemplate.batchUpdate("""
                INSERT INTO sections (id, venue_id, code, default_tier) VALUES (?, ?, ?, ?)
                ON CONFLICT (id) DO NOTHING
                """, rows);
    }

    private int insertSeats(VenueFile venue) {
        List<Object[]> rows = new ArrayList<>();
        for (VenueFile.Section section : venue.sections()) {
            UUID sectionId = SeedIds.section(venue.slug(), section.code());
            for (VenueFile.Row row : section.rows()) {
                for (VenueFile.Seat seat : row.seats()) {
                    rows.add(new Object[] {
                            SeedIds.seat(venue.slug(), section.code(), row.label(), seat.number()),
                            sectionId, row.label(), seat.number(), seat.x(), seat.y(), attrsJson(seat)});
                }
            }
        }
        int[] counts = jdbcTemplate.batchUpdate("""
                INSERT INTO seats (id, section_id, row_label, number, x, y, attrs)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb)
                ON CONFLICT (id) DO NOTHING
                """, rows);
        return Arrays.stream(counts).sum();  // ON CONFLICT DO NOTHING reports 0 for skipped rows
    }

    private void insertEvent(UUID eventId, UUID venueId, EventSeedFile event) {
        Instant now = Instant.now();
        OffsetDateTime dropAt = utc(now.plus(Duration.ofHours(event.dropInHours())));
        OffsetDateTime startsAt = utc(now.plus(Duration.ofDays(event.startsInDays())));
        jdbc.sql("""
                INSERT INTO events (id, venue_id, title, starts_at, drop_at, status, per_user_limit, strategy)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (id) DO NOTHING
                """).params(eventId, venueId, event.title(), startsAt, dropAt,
                        event.status(), event.perUserLimit(), event.strategy())
                .update();
    }

    private void insertTiers(UUID eventId, EventSeedFile event) {
        List<Object[]> rows = event.tiers().stream()
                .map(t -> new Object[] {SeedIds.tier(event.slug(), t.name()), eventId, t.name(), t.pricePaise()})
                .toList();
        jdbcTemplate.batchUpdate("""
                INSERT INTO price_tiers (id, event_id, name, price_paise) VALUES (?, ?, ?, ?)
                ON CONFLICT (id) DO NOTHING
                """, rows);
    }

    /**
     * One AVAILABLE event seat per seat of the event's own venue, priced by the section's default tier.
     * Joining through sections.venue_id = events.venue_id means a seat from another venue can't be added.
     */
    private int insertEventSeats(UUID eventId) {
        return jdbc.sql("""
                INSERT INTO event_seats (event_id, seat_id, tier_id, status)
                SELECT e.id, s.id, t.id, 'AVAILABLE'
                FROM events e
                JOIN sections sec   ON sec.venue_id = e.venue_id
                JOIN seats s        ON s.section_id = sec.id
                JOIN price_tiers t  ON t.event_id = e.id AND t.name = sec.default_tier
                WHERE e.id = ?
                ON CONFLICT (event_id, seat_id) DO NOTHING
                """).param(eventId).update();
    }

    // ---------------------------------------------------------------------------------------------
    // Verification: the database must now match the files exactly
    // ---------------------------------------------------------------------------------------------

    private void verifyMatchesFiles(UUID venueId, UUID eventId, VenueFile venue, EventSeedFile event) {
        List<String> diffs = new ArrayList<>();

        Boolean venueMatches = jdbc.sql("SELECT layout_json = ?::jsonb AND name = ? FROM venues WHERE id = ?")
                .params(SeedFiles.layoutJson(venue), venue.name(), venueId)
                .query(Boolean.class).single();
        if (!venueMatches) {
            diffs.add("venue '" + venue.slug() + "' name or layout (stage, section outlines/labels) differs");
        }

        Map<String, String> fileSections = new HashMap<>();
        venue.sections().forEach(s -> fileSections.put(s.code(), s.defaultTier()));
        Map<String, String> dbSections = jdbc.sql("SELECT code, default_tier FROM sections WHERE venue_id = ?")
                .param(venueId)
                .query((rs, n) -> Map.entry(rs.getString(1), rs.getString(2))).list().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        if (!fileSections.equals(dbSections)) {
            diffs.add("sections differ: file " + fileSections + ", database " + dbSections);
        }

        Set<String> fileSeats = new HashSet<>();
        for (VenueFile.Section s : venue.sections()) {
            for (VenueFile.Row r : s.rows()) {
                for (VenueFile.Seat seat : r.seats()) {
                    fileSeats.add(seatKey(s.code(), r.label(), seat.number(), seat.x(), seat.y(),
                            seat.isAisle(), seat.isAccessible()));
                }
            }
        }
        Set<String> dbSeats = new HashSet<>(jdbc.sql("""
                SELECT sec.code, s.row_label, s.number, s.x, s.y,
                       (s.attrs ->> 'aisle')::boolean, (s.attrs ->> 'accessible')::boolean
                FROM seats s JOIN sections sec ON sec.id = s.section_id
                WHERE sec.venue_id = ?
                """).param(venueId)
                .query((rs, n) -> seatKey(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getDouble(4),
                        rs.getDouble(5), rs.getBoolean(6), rs.getBoolean(7)))
                .list());
        if (!fileSeats.equals(dbSeats)) {
            Set<String> onlyInFile = new HashSet<>(fileSeats);
            onlyInFile.removeAll(dbSeats);
            Set<String> onlyInDb = new HashSet<>(dbSeats);
            onlyInDb.removeAll(fileSeats);
            diffs.add("seats differ (" + onlyInFile.size() + " only in the file, e.g. " + sample(onlyInFile)
                    + "; " + onlyInDb.size() + " only in the database, e.g. " + sample(onlyInDb) + ")");
        }

        record EventRow(UUID venueId, String title) {}
        EventRow dbEvent = jdbc.sql("SELECT venue_id, title FROM events WHERE id = ?").param(eventId)
                .query((rs, n) -> new EventRow(rs.getObject(1, UUID.class), rs.getString(2))).single();
        if (!dbEvent.venueId().equals(venueId) || !dbEvent.title().equals(event.title())) {
            diffs.add("event '" + event.slug() + "' venue or title differs");
        }

        Map<String, Long> fileTiers = new HashMap<>();
        event.tiers().forEach(t -> fileTiers.put(t.name(), t.pricePaise()));
        Map<String, Long> dbTiers = jdbc.sql("SELECT name, price_paise FROM price_tiers WHERE event_id = ?")
                .param(eventId)
                .query((rs, n) -> Map.entry(rs.getString(1), rs.getLong(2))).list().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        if (!fileTiers.equals(dbTiers)) {
            diffs.add("price tiers differ: file " + fileTiers + ", database " + dbTiers);
        }

        if (diffs.isEmpty()) {
            verifyEventSeats(eventId, venue.seatCount(), diffs);
        }

        if (!diffs.isEmpty()) {
            throw new SeedException("Seed data in the database differs from the seed files (rolled back, "
                    + "nothing was written):\n  - " + String.join("\n  - ", diffs) + "\n" + RESET_HINT);
        }
    }

    /** Every seat of the venue has exactly one event seat, from the right venue, in the right tier. */
    private void verifyEventSeats(UUID eventId, int expectedSeats, List<String> diffs) {
        int eventSeats = jdbc.sql("SELECT count(*) FROM event_seats WHERE event_id = ?")
                .param(eventId).query(Integer.class).single();
        if (eventSeats != expectedSeats) {
            diffs.add("event has " + eventSeats + " event seats, expected " + expectedSeats);
        }
        int wrongVenue = jdbc.sql("""
                SELECT count(*) FROM event_seats es
                JOIN events e     ON e.id = es.event_id
                JOIN seats s      ON s.id = es.seat_id
                JOIN sections sec ON sec.id = s.section_id
                WHERE es.event_id = ? AND sec.venue_id <> e.venue_id
                """).param(eventId).query(Integer.class).single();
        if (wrongVenue != 0) {
            diffs.add(wrongVenue + " event seats belong to a different venue than the event");
        }
        int wrongTier = jdbc.sql("""
                SELECT count(*) FROM event_seats es
                JOIN seats s       ON s.id = es.seat_id
                JOIN sections sec  ON sec.id = s.section_id
                JOIN price_tiers t ON t.id = es.tier_id
                WHERE es.event_id = ? AND t.name <> sec.default_tier
                """).param(eventId).query(Integer.class).single();
        if (wrongTier != 0) {
            diffs.add(wrongTier + " event seats are not in their section's default tier");
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    private static String attrsJson(VenueFile.Seat seat) {
        return "{\"aisle\": " + seat.isAisle() + ", \"accessible\": " + seat.isAccessible() + "}";
    }

    private static String seatKey(String section, String row, int number, double x, double y,
                                  boolean aisle, boolean accessible) {
        return section + "/" + row + "/" + number + " at (" + x + ", " + y + ")"
                + (aisle ? " aisle" : "") + (accessible ? " accessible" : "");
    }

    private static String sample(Set<String> keys) {
        return keys.stream().sorted().limit(3).toList().toString();
    }

    private static OffsetDateTime utc(Instant instant) {
        return Objects.requireNonNull(instant).atOffset(ZoneOffset.UTC);
    }
}
