package com.stampede.inventory.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.StreamSupport;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;

import com.fasterxml.jackson.databind.JsonNode;
import com.stampede.inventory.seed.EventSeedFile;
import com.stampede.inventory.seed.SeedFiles;
import com.stampede.inventory.seed.SeedIds;
import com.stampede.inventory.seed.SeedLoader;
import com.stampede.inventory.seed.VenueFile;
import com.stampede.testsupport.ApiTestSupport;

/**
 * GET /events/{id}/layout and GET /events/{id}/seatmap over HTTP (M1.8, D-020). Every read is sent
 * without a token: both routes are public.
 *
 * <p>The layout is checked against the seeded demo venue, seat by seat. Status changes are checked on
 * a small event of its own (five seats), so holding and buying seats doesn't touch the demo event
 * other tests read. Its seat ids are chosen so that byte-wise uuid order (PostgreSQL) differs from
 * Java's signed {@link UUID#compareTo}: the API promises the PostgreSQL order.
 */
class SeatMapApiTest extends ApiTestSupport {

    private static final UUID DEMO_EVENT = SeedIds.event("demo-drop");

    static final UUID VENUE = UUID.fromString("00000000-0000-4000-8000-0000000002a1");
    static final UUID SECTION = UUID.fromString("00000000-0000-4000-8000-0000000002a2");
    static final UUID EVENT = UUID.fromString("00000000-0000-4000-8000-0000000002a3");
    static final UUID TIER = UUID.fromString("00000000-0000-4000-8000-0000000002a4");
    /** Inserted in this order; sorted byte-wise they are 1, 3, 5, 9, b (b0… is negative for Java). */
    static final List<UUID> SEATS = List.of(
            UUID.fromString("b0000000-0000-4000-8000-0000000002b1"),
            UUID.fromString("30000000-0000-4000-8000-0000000002b2"),
            UUID.fromString("90000000-0000-4000-8000-0000000002b3"),
            UUID.fromString("10000000-0000-4000-8000-0000000002b4"),
            UUID.fromString("50000000-0000-4000-8000-0000000002b5"));
    /** {@link #SEATS} in the API's order: byte-wise, i.e. by the lowercase text form. */
    static final List<UUID> SORTED = SEATS.stream().sorted(Comparator.comparing(UUID::toString)).toList();

    private static final String LAYOUT_JSON = """
            {"formatVersion": 1, "slug": "seatmap-test", "name": "Seat map venue",
             "width": 200, "height": 100, "seatRadius": 4,
             "stage": {"x": 50.0, "y": 5.0, "width": 100.0, "height": 10.0},
             "sections": [{"code": "A", "name": "Section A", "label": {"x": 100.0, "y": 30.0},
                           "outline": [[10.0, 20.0], [190.0, 20.0], [190.0, 90.0], [10.0, 90.0]]}]}
            """;

    @Autowired
    SeedLoader seedLoader;

    private String alice;
    private final List<UUID> extraEvents = new ArrayList<>();
    private final List<UUID> extraVenues = new ArrayList<>();

    @BeforeEach
    void seedDemoAndInsertEvent() {
        seedLoader.load(demoVenue(), demoEvent());  // idempotent

        insertVenue(VENUE, LAYOUT_JSON);
        jdbc.sql("INSERT INTO sections (id, venue_id, code, default_tier) VALUES (?, ?, 'A', 'GOLD')")
                .params(SECTION, VENUE).update();
        jdbc.sql("""
                INSERT INTO events (id, venue_id, title, starts_at, drop_at, status, per_user_limit, strategy)
                VALUES (?, ?, 'Seat map test event', now() + interval '30 days', now() - interval '1 hour',
                        'LIVE', 4, 'PESSIMISTIC')
                """).params(EVENT, VENUE).update();
        jdbc.sql("INSERT INTO price_tiers (id, event_id, name, price_paise) VALUES (?, ?, 'GOLD', 150000)")
                .params(TIER, EVENT).update();
        for (int i = 0; i < SEATS.size(); i++) {
            jdbc.sql("""
                    INSERT INTO seats (id, section_id, row_label, number, x, y, attrs)
                    VALUES (?, ?, 'A', ?, ?, 50, ?::jsonb)
                    """)
                    .params(SEATS.get(i), SECTION, i + 1, 20.0 + 10 * i,
                            i == 0 ? "{\"aisle\": true, \"accessible\": false}" : "{}")
                    .update();
            jdbc.sql("INSERT INTO event_seats (event_id, seat_id, tier_id, status) VALUES (?, ?, ?, 'AVAILABLE')")
                    .params(EVENT, SEATS.get(i), TIER).update();
        }
        alice = registerAndLogin(uniqueEmail()).accessToken();
    }

    /** Other tests share this database (and GET /events must only list the demo event there). */
    @AfterEach
    void deleteEvent() {
        jdbc.sql("DELETE FROM tickets WHERE event_id = ?").param(EVENT).update();
        jdbc.sql("DELETE FROM payments WHERE order_id IN (SELECT id FROM orders WHERE event_id = ?)")
                .param(EVENT).update();
        jdbc.sql("DELETE FROM order_items WHERE event_id = ?").param(EVENT).update();
        jdbc.sql("DELETE FROM event_seats WHERE event_id = ?").param(EVENT).update();
        jdbc.sql("DELETE FROM orders WHERE event_id = ?").param(EVENT).update();
        jdbc.sql("DELETE FROM holds WHERE event_id = ?").param(EVENT).update();
        jdbc.sql("DELETE FROM user_event_allowance WHERE event_id = ?").param(EVENT).update();
        extraEvents.add(EVENT);
        extraEvents.forEach(id -> jdbc.sql("DELETE FROM price_tiers WHERE event_id = ?").param(id).update());
        extraEvents.forEach(id -> jdbc.sql("DELETE FROM events WHERE id = ?").param(id).update());
        jdbc.sql("DELETE FROM seats WHERE section_id = ?").param(SECTION).update();
        jdbc.sql("DELETE FROM sections WHERE id = ?").param(SECTION).update();
        extraVenues.add(VENUE);
        extraVenues.forEach(id -> jdbc.sql("DELETE FROM venues WHERE id = ?").param(id).update());
    }

    // --- GET /events/{id}/layout -----------------------------------------------------------------

    @Test
    void demoLayoutMatchesTheVenueFileSeatBySeatWithoutAToken() {
        VenueFile file = demoVenue();
        Response response = get("/events/" + DEMO_EVENT + "/layout", null);

        assertThat(response.status()).isEqualTo(200);
        JsonNode body = response.body();
        assertThat(body.path("eventId").asText()).isEqualTo(DEMO_EVENT.toString());

        JsonNode venue = body.path("venue");
        assertThat(venue.path("id").asText()).isEqualTo(SeedIds.venue("demo-arena").toString());
        assertThat(venue.path("name").asText()).isEqualTo(file.name());
        assertThat(venue.path("width").asInt()).isEqualTo(file.width());
        assertThat(venue.path("height").asInt()).isEqualTo(file.height());
        assertThat(venue.path("seatRadius").asInt()).isEqualTo(file.seatRadius());
        assertThat(venue.path("stage").path("x").asDouble()).isEqualTo(file.stage().x());
        assertThat(venue.path("stage").path("width").asDouble()).isEqualTo(file.stage().width());
        assertThat(venue.path("sections").size()).isEqualTo(file.sections().size());
        for (int i = 0; i < file.sections().size(); i++) {
            VenueFile.Section expected = file.sections().get(i);
            JsonNode actual = venue.path("sections").get(i);
            assertThat(actual.path("code").asText()).isEqualTo(expected.code());
            assertThat(actual.path("name").asText()).isEqualTo(expected.name());
            assertThat(actual.path("label").path("y").asDouble()).isEqualTo(expected.label().y());
            assertThat(actual.path("outline").size()).isEqualTo(expected.outline().size());
            assertThat(actual.path("outline").get(0).get(0).asDouble()).isEqualTo(expected.outline().get(0).get(0));
            // Seats are only in the seats table, never in the layout (docs/venue-format.md).
            assertThat(actual.has("rows")).isFalse();
        }

        // Every seat of the file, exactly once, with its position, label, tier and attributes.
        Map<UUID, ExpectedSeat> expected = expectedSeats(file);
        JsonNode seats = body.path("seats");
        assertThat(seats.size()).isEqualTo(file.seatCount()).isEqualTo(2014);
        Map<UUID, JsonNode> actualById = new HashMap<>();
        seats.forEach(seat -> actualById.put(UUID.fromString(seat.path("id").asText()), seat));
        assertThat(actualById.keySet()).isEqualTo(expected.keySet());
        expected.forEach((id, seat) -> {
            JsonNode actual = actualById.get(id);
            assertThat(actual.path("section").asText()).as("section of %s", id).isEqualTo(seat.section());
            assertThat(actual.path("row").asText()).as("row of %s", id).isEqualTo(seat.row());
            assertThat(actual.path("number").asInt()).as("number of %s", id).isEqualTo(seat.number());
            assertThat(actual.path("x").asDouble()).as("x of %s", id).isEqualTo(seat.x());
            assertThat(actual.path("y").asDouble()).as("y of %s", id).isEqualTo(seat.y());
            assertThat(actual.path("tierId").asText()).as("tier of %s", id).isEqualTo(seat.tierId().toString());
            assertThat(actual.path("aisle").asBoolean()).as("aisle of %s", id).isEqualTo(seat.aisle());
            assertThat(actual.path("accessible").asBoolean()).as("accessible of %s", id).isEqualTo(seat.accessible());
        });
        assertThat(ids(seats)).isSortedAccordingTo(Comparator.comparing(UUID::toString));
    }

    @Test
    void layoutListsSeatsInByteWiseIdOrderWithTheirAttributes() {
        Response response = get("/events/" + EVENT + "/layout", null);

        assertThat(response.status()).isEqualTo(200);
        JsonNode seats = response.body().path("seats");
        assertThat(ids(seats)).containsExactlyElementsOf(SORTED);
        // Java's UUID.compareTo is signed, so it would put b0… first: the order is not Java's.
        assertThat(SORTED).isNotEqualTo(SEATS.stream().sorted().toList());

        JsonNode b0 = seats.get(SORTED.indexOf(SEATS.get(0)));
        assertThat(b0.path("aisle").asBoolean()).isTrue();
        assertThat(b0.path("accessible").asBoolean()).isFalse();
        JsonNode other = seats.get(SORTED.indexOf(SEATS.get(1)));  // attrs '{}': both default to false
        assertThat(other.path("aisle").asBoolean()).isFalse();
        assertThat(other.path("accessible").asBoolean()).isFalse();
        assertThat(other.path("tierId").asText()).isEqualTo(TIER.toString());
        assertThat(response.body().path("venue").path("sections").get(0).path("code").asText()).isEqualTo("A");
    }

    @Test
    void venueWithAnIncompleteLayoutIs500WithoutDetails() {
        UUID venue = UUID.randomUUID();
        insertVenue(venue, "{}");
        UUID event = insertEvent(venue, "SCHEDULED");

        Response response = get("/events/" + event + "/layout", null);

        assertThat(response.status()).isEqualTo(500);
        assertThat(response.code()).isEqualTo("INTERNAL_ERROR");
        assertThat(response.body().path("detail").asText()).doesNotContain("layout_json");
    }

    // --- GET /events/{id}/seatmap ----------------------------------------------------------------

    @Test
    void demoSeatMapHasOneBitPerLayoutSeatMatchingTheDatabase() {
        Response response = get("/events/" + DEMO_EVENT + "/seatmap", null);

        assertThat(response.status()).isEqualTo(200);
        JsonNode body = response.body();
        assertThat(body.path("eventId").asText()).isEqualTo(DEMO_EVENT.toString());
        assertThat(body.path("seatCount").asInt()).isEqualTo(2014);
        byte[] bytes = Base64.getDecoder().decode(body.path("available").asText());
        assertThat(bytes).hasSize(252);  // ceil(2014 / 8)
        assertThat(decode(body)).containsExactlyElementsOf(availableInDatabase(DEMO_EVENT));
        Instant asOf = Instant.parse(body.path("asOf").asText());
        assertThat(Duration.between(asOf, Instant.now()).abs()).isLessThan(Duration.ofMinutes(1));
    }

    @Test
    void freshEventIsAllAvailableWithUnusedBitsZero() {
        JsonNode body = get("/events/" + EVENT + "/seatmap", null).body();

        assertThat(body.path("seatCount").asInt()).isEqualTo(5);
        // 5 seats, most significant bit first: 1111 1000.
        assertThat(Base64.getDecoder().decode(body.path("available").asText())).containsExactly((byte) 0b1111_1000);
    }

    @Test
    void heldSoldAndReleasedSeatsShowInTheirLayoutPositions() {
        // seats[0] (10…) and seats[4] (b0…) in a hold that is bought; seats[2] (50…) held, then released.
        UUID bought = holdId(postHold(alice, sorted(0), sorted(4)));
        UUID released = holdId(postHold(alice, sorted(2)));

        assertThat(availableBits()).containsExactly(false, true, false, true, false);

        Response order = exchange(HttpMethod.POST, "/orders",
                new HttpEntity<>(Map.of("holdId", bought), jsonHeaders(alice)));
        assertThat(order.status()).isEqualTo(201);
        assertThat(availableBits()).containsExactly(false, true, false, true, false);

        assertThat(request(HttpMethod.DELETE, "/holds/" + released, alice).status()).isEqualTo(204);
        assertThat(availableBits()).containsExactly(false, true, true, true, false);
        assertThat(decode(get("/events/" + EVENT + "/seatmap", null).body()))
                .containsExactlyElementsOf(availableInDatabase(EVENT));
    }

    @Test
    void eventWithoutSeatsHasAnEmptySeatMapAndLayout() {
        UUID empty = insertEvent(VENUE, "SCHEDULED");

        JsonNode seatMap = get("/events/" + empty + "/seatmap", null).body();
        assertThat(seatMap.path("seatCount").asInt()).isZero();
        assertThat(seatMap.path("available").asText()).isEmpty();

        JsonNode layout = get("/events/" + empty + "/layout", null).body();
        assertThat(layout.path("seats").isArray()).isTrue();
        assertThat(layout.path("seats").size()).isZero();
    }

    // --- Errors and security, both endpoints -----------------------------------------------------

    @Test
    void unknownAndDraftEventsAre404AndMalformedIdsAre400() {
        UUID draft = insertEvent(VENUE, "DRAFT");
        for (String part : List.of("layout", "seatmap")) {
            Response unknown = get("/events/" + UUID.randomUUID() + "/" + part, null);
            assertThat(unknown.status()).as(part).isEqualTo(404);
            assertThat(unknown.code()).as(part).isEqualTo("EVENT_NOT_FOUND");
            assertThat(unknown.headers().getContentType().isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .isTrue();

            assertThat(get("/events/" + draft + "/" + part, null).code()).as(part).isEqualTo("EVENT_NOT_FOUND");

            Response malformed = get("/events/not-a-uuid/" + part, null);
            assertThat(malformed.status()).as(part).isEqualTo(400);
            assertThat(malformed.code()).as(part).isEqualTo("VALIDATION_FAILED");
        }
    }

    @Test
    void onlyTheTwoSeatMapPathsArePublicUnderAnEvent() {
        // GET /events/{id}/holds has no route. Without a token, security answers first (401), which
        // shows the new rule didn't open other paths under /events/{id}/; with one, it is 405.
        assertThat(get("/events/" + EVENT + "/holds", null).status()).isEqualTo(401);
        assertThat(get("/events/" + EVENT + "/anything", null).status()).isEqualTo(401);
        assertThat(get("/events/" + EVENT + "/holds", alice).code()).isEqualTo("METHOD_NOT_ALLOWED");
        // Only GET is public.
        assertThat(request(HttpMethod.DELETE, "/events/" + EVENT + "/seatmap", null).status()).isEqualTo(401);
    }

    // ---------------------------------------------------------------------------------------------

    private record ExpectedSeat(String section, String row, int number, double x, double y, UUID tierId,
                                boolean aisle, boolean accessible) {}

    private static Map<UUID, ExpectedSeat> expectedSeats(VenueFile file) {
        Map<UUID, ExpectedSeat> seats = new HashMap<>();
        for (VenueFile.Section section : file.sections()) {
            UUID tier = SeedIds.tier("demo-drop", section.defaultTier());
            for (VenueFile.Row row : section.rows()) {
                for (VenueFile.Seat seat : row.seats()) {
                    seats.put(SeedIds.seat(file.slug(), section.code(), row.label(), seat.number()),
                            new ExpectedSeat(section.code(), row.label(), seat.number(), seat.x(), seat.y(), tier,
                                    seat.isAisle(), seat.isAccessible()));
                }
            }
        }
        return seats;
    }

    private static VenueFile demoVenue() {
        return SeedFiles.readVenue(new ClassPathResource("seed/venues/demo-arena.json"));
    }

    private static EventSeedFile demoEvent() {
        return SeedFiles.readEvent(new ClassPathResource("seed/events/demo-drop.json"));
    }

    private void insertVenue(UUID id, String layoutJson) {
        jdbc.sql("INSERT INTO venues (id, name, layout_json) VALUES (?, 'Seat map venue', ?::jsonb)")
                .params(id, layoutJson).update();
        if (!id.equals(VENUE)) {
            extraVenues.add(id);
        }
    }

    private UUID insertEvent(UUID venue, String status) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO events (id, venue_id, title, starts_at, drop_at, status, per_user_limit, strategy)
                VALUES (?, ?, 'Extra event', now() + interval '30 days', now() + interval '1 day', ?, 4, 'PESSIMISTIC')
                """).params(id, venue, status).update();
        extraEvents.add(id);
        return id;
    }

    private static UUID sorted(int position) {
        return SORTED.get(position);
    }

    private List<Boolean> availableBits() {
        return decode(get("/events/" + EVENT + "/seatmap", null).body());
    }

    /** Reads the bitset the way docs/venue-format.md describes it to clients. */
    private static List<Boolean> decode(JsonNode seatMap) {
        byte[] bytes = Base64.getDecoder().decode(seatMap.path("available").asText());
        int count = seatMap.path("seatCount").asInt();
        List<Boolean> bits = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            bits.add((bytes[i / 8] & (0x80 >>> (i % 8))) != 0);
        }
        return bits;
    }

    private List<Boolean> availableInDatabase(UUID event) {
        return jdbc.sql("SELECT status = 'AVAILABLE' FROM event_seats WHERE event_id = ? ORDER BY seat_id")
                .param(event).query(Boolean.class).list();
    }

    private Response postHold(String token, UUID... seats) {
        return exchange(HttpMethod.POST, "/events/" + EVENT + "/holds",
                new HttpEntity<>(Map.of("seatIds", List.of(seats)), jsonHeaders(token)));
    }

    private static UUID holdId(Response response) {
        assertThat(response.status()).isEqualTo(201);
        return UUID.fromString(response.body().path("holdId").asText());
    }

    private static HttpHeaders jsonHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        return headers;
    }

    private static List<UUID> ids(JsonNode seats) {
        return StreamSupport.stream(seats.spliterator(), false)
                .map(seat -> UUID.fromString(seat.path("id").asText()))
                .toList();
    }
}
