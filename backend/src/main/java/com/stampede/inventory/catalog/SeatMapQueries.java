package com.stampede.inventory.catalog;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stampede.inventory.catalog.SeatMapViews.EventLayout;
import com.stampede.inventory.catalog.SeatMapViews.LayoutSeat;
import com.stampede.inventory.catalog.SeatMapViews.LayoutSection;
import com.stampede.inventory.catalog.SeatMapViews.LayoutRect;
import com.stampede.inventory.catalog.SeatMapViews.SeatMap;
import com.stampede.inventory.catalog.SeatMapViews.VenueLayout;
import com.stampede.inventory.strategy.RedisSeatAvailability;
import com.stampede.inventory.strategy.RedisSeatAvailability.Read;

/**
 * Read-only SQL for the seat map (D-020). Reads only inventory's own tables, takes no locks: a plain
 * SELECT reads a snapshot and never waits for the row locks that holds take, so polling the seat map
 * doesn't slow buyers down.
 *
 * <p>Both queries list seats {@code ORDER BY es.seat_id}, which is how the seat map's bit index and
 * the layout's seat list line up. The primary key of event_seats is (event_id, seat_id), so this order
 * comes straight from the index.
 *
 * <p><b>REDIS_FIRST events read Redis</b> (M2.4a-seatmap): Redis decides their holds, and Postgres trails
 * it by the stream consumer's lag, so a Postgres seat map showed new holds as free and expired ones as
 * taken for seconds. The Postgres statement still runs (existence, strategy, seat order, statuses);
 * {@link RedisSeatAvailability} then says which seats Redis has taken. A seat Postgres has SOLD is never
 * shown free, whatever Redis says (a sold marker Redis lost). If Redis can't answer, the Postgres answer
 * is returned. Counted in {@code stampede.inventory.seatmap.reads} by source and reason.
 */
@Component
class SeatMapQueries {

    static final String READS = "stampede.inventory.seatmap.reads";

    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final RedisSeatAvailability redisSeats;
    private final MeterRegistry meters;

    SeatMapQueries(JdbcClient jdbc, ObjectMapper json, RedisSeatAvailability redisSeats, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.json = json;
        this.redisSeats = redisSeats;
        this.meters = meters;
    }

    /**
     * The event's static layout, or empty if the event doesn't exist or is a DRAFT.
     *
     * <p>Two statements, unlike the event detail (D-010): venues, sections, seats and the seats' tiers
     * don't change after seeding, so there is no moment at which the two reads could disagree.
     */
    Optional<EventLayout> layout(UUID eventId) {
        Optional<VenueRow> venue = jdbc.sql("""
                SELECT v.id, v.name, v.layout_json::text AS layout_json
                FROM events e
                JOIN venues v ON v.id = e.venue_id
                WHERE e.id = ? AND e.status <> 'DRAFT'
                """)
                .param(eventId)
                .query((rs, n) -> new VenueRow(
                        rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("layout_json")))
                .optional();
        if (venue.isEmpty()) {
            return Optional.empty();
        }
        List<LayoutSeat> seats = jdbc.sql("""
                SELECT s.id, sec.code, s.row_label, s.number, s.x, s.y, es.tier_id,
                       coalesce((s.attrs ->> 'aisle')::boolean, false)      AS aisle,
                       coalesce((s.attrs ->> 'accessible')::boolean, false) AS accessible
                FROM event_seats es
                JOIN seats s      ON s.id = es.seat_id
                JOIN sections sec ON sec.id = s.section_id
                WHERE es.event_id = ?
                ORDER BY es.seat_id
                """)
                .param(eventId)
                .query((rs, n) -> new LayoutSeat(
                        rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("row_label"),
                        rs.getInt("number"), rs.getDouble("x"), rs.getDouble("y"),
                        rs.getObject("tier_id", UUID.class), rs.getBoolean("aisle"), rs.getBoolean("accessible")))
                .list();
        return Optional.of(new EventLayout(eventId, venueLayout(venue.get()), seats));
    }

    /**
     * Which seats are available now, or empty if the event doesn't exist or is a DRAFT.
     *
     * <p>One statement, so the existence check, the strategy, the seats and {@code asOf} come from the same
     * snapshot. The LEFT JOIN keeps one row (with a null seat) for an event without seats. {@code now()} is
     * the statement's start time here: JdbcClient runs it in autocommit, outside any transaction. For a
     * REDIS_FIRST event Redis answers if it can (see the class comment), and {@code asOf} is then Redis'
     * clock when its read started.
     */
    Optional<SeatMap> seatMap(UUID eventId) {
        List<StatusRow> rows = jdbc.sql("""
                SELECT now() AS as_of, e.strategy, es.seat_id, es.status
                FROM events e
                LEFT JOIN event_seats es ON es.event_id = e.id
                WHERE e.id = ? AND e.status <> 'DRAFT'
                ORDER BY es.seat_id
                """)
                .param(eventId)
                .query((rs, n) -> new StatusRow(rs.getObject("as_of", OffsetDateTime.class).toInstant(),
                        rs.getString("strategy"), rs.getObject("seat_id", UUID.class), rs.getString("status")))
                .list();
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Instant asOf = rows.get(0).asOf();
        List<StatusRow> seats = rows.stream().filter(row -> row.seatId() != null).toList();  // none: no seats

        List<Boolean> takenInRedis = null;
        if (rows.get(0).strategy().equals("REDIS_FIRST")) {
            Read read = redisSeats.read(eventId, seats.stream().map(StatusRow::seatId).toList());
            switch (read) {
                case Read.Fresh fresh -> {
                    takenInRedis = fresh.taken();
                    asOf = fresh.asOf();
                    count("redis", "none");
                }
                case Read.Unavailable unavailable -> count("fallback", unavailable.reason().name().toLowerCase());
            }
        } else {
            count("postgres", "none");
        }

        List<Boolean> available = new ArrayList<>(seats.size());
        for (int i = 0; i < seats.size(); i++) {
            String status = seats.get(i).status();
            available.add(takenInRedis == null
                    ? status.equals("AVAILABLE")
                    : !takenInRedis.get(i) && !status.equals("SOLD"));
        }
        return Optional.of(new SeatMap(eventId, available.size(), encode(available), asOf));
    }

    private void count(String source, String reason) {
        Counter.builder(READS)
                .description("Seat map reads by where the answer came from: postgres (not REDIS_FIRST), "
                        + "redis, or fallback (REDIS_FIRST answered from Postgres, with the reason)")
                .tag("source", source)
                .tag("reason", reason)
                .register(meters)
                .increment();
    }

    /**
     * Packs one bit per seat, most significant bit first, and base64-encodes the bytes (format in
     * {@link SeatMapViews#BITSET_FORMAT}). 2,014 seats become 252 bytes, 336 characters.
     */
    static String encode(List<Boolean> available) {
        byte[] bytes = new byte[(available.size() + 7) / 8];
        for (int i = 0; i < available.size(); i++) {
            if (available.get(i)) {
                bytes[i / 8] |= (byte) (0x80 >>> (i % 8));
            }
        }
        return Base64.getEncoder().encodeToString(bytes);
    }

    private VenueLayout venueLayout(VenueRow venue) {
        StoredLayout stored;
        try {
            stored = json.readValue(venue.layoutJson(), StoredLayout.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("venues.layout_json of venue " + venue.id() + " is not valid JSON", e);
        }
        if (stored.width() == null || stored.height() == null || stored.seatRadius() == null
                || stored.stage() == null || stored.sections() == null) {
            // Seeded venues always have these (the seed validator checks them); anything else is a data bug: 500.
            throw new IllegalStateException("venues.layout_json of venue " + venue.id() + " is incomplete");
        }
        return new VenueLayout(venue.id(), venue.name(), stored.width(), stored.height(), stored.seatRadius(),
                stored.stage(), stored.sections());
    }

    private record VenueRow(UUID id, String name, String layoutJson) {}

    private record StatusRow(Instant asOf, String strategy, UUID seatId, String status) {}

    /**
     * What the seed loader writes to {@code venues.layout_json} (SeedFiles.layoutJson). The format
     * version, slug and name are stored too but not needed here.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record StoredLayout(Integer width, Integer height, Integer seatRadius, LayoutRect stage,
                                List<LayoutSection> sections) {}
}
