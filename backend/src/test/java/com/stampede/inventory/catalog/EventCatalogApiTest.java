package com.stampede.inventory.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.StreamSupport;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;

import com.fasterxml.jackson.databind.JsonNode;
import com.stampede.inventory.seed.SeedFiles;
import com.stampede.inventory.seed.SeedIds;
import com.stampede.inventory.seed.SeedLoader;
import com.stampede.testsupport.ApiTestSupport;

/**
 * GET /events and GET /events/{id} against the seeded demo event. Every request is sent without a
 * token: both routes are public.
 */
class EventCatalogApiTest extends ApiTestSupport {

    private static final UUID DEMO_EVENT = SeedIds.event("demo-drop");
    private static final UUID DEMO_VENUE = SeedIds.venue("demo-arena");

    @Autowired
    SeedLoader seedLoader;

    /** Events a test inserted; deleted afterwards so other tests see only the demo event. */
    private final List<UUID> extraEvents = new ArrayList<>();

    @BeforeEach
    void seedDemo() {
        // Idempotent: loads once per test database, then only verifies.
        seedLoader.load(
                SeedFiles.readVenue(new ClassPathResource("seed/venues/demo-arena.json")),
                SeedFiles.readEvent(new ClassPathResource("seed/events/demo-drop.json")));
    }

    @AfterEach
    void deleteExtraEvents() {
        extraEvents.forEach(id -> jdbc.sql("DELETE FROM price_tiers WHERE event_id = ?").param(id).update());
        extraEvents.forEach(id -> jdbc.sql("DELETE FROM events WHERE id = ?").param(id).update());
    }

    // ---------------------------------------------------------------------------------------------
    // GET /events
    // ---------------------------------------------------------------------------------------------

    @Test
    void listReturnsTheDemoEventWithoutAToken() {
        Response response = get("/events", null);

        assertThat(response.status()).isEqualTo(200);
        JsonNode demo = item(response, DEMO_EVENT);
        assertThat(demo).as("demo event in the list").isNotNull();
        assertThat(demo.path("title").asText()).isEqualTo("Stampede Demo Drop");
        assertThat(demo.path("status").asText()).isEqualTo("SCHEDULED");
        assertThat(demo.path("perUserLimit").asInt()).isEqualTo(4);
        assertThat(demo.path("venue").path("id").asText()).isEqualTo(DEMO_VENUE.toString());
        assertThat(demo.path("venue").path("name").asText()).isEqualTo("Stampede Demo Arena");
        assertThat(Instant.parse(demo.path("dropAt").asText()))
                .isBefore(Instant.parse(demo.path("startsAt").asText()));
        // The board needs no tiers, and the inventory strategy is an operator detail.
        assertThat(demo.has("tiers")).isFalse();
        assertThat(demo.has("strategy")).isFalse();
        assertServerTimeIsNow(response);
    }

    @Test
    void listHidesDraftEndedAndStartedEventsButShowsSoldOut() {
        Instant now = Instant.now();
        UUID draft = insertEvent("DRAFT", now.plus(days(1)), now.plus(days(2)));
        UUID ended = insertEvent("ENDED", now.plus(days(1)), now.plus(days(2)));
        UUID started = insertEvent("SCHEDULED", now.minus(days(2)), now.minus(days(1)));
        UUID soldOut = insertEvent("SOLD_OUT", now.minus(days(1)), now.plus(days(1)));

        List<String> ids = ids(get("/events", null));

        assertThat(ids).contains(soldOut.toString(), DEMO_EVENT.toString());
        assertThat(ids).doesNotContain(draft.toString(), ended.toString(), started.toString());
    }

    @Test
    void listIsOrderedByDropTimeAndCappedAt100() {
        Instant now = Instant.now();
        for (int i = 0; i < 105; i++) {
            // Drop times from 1 minute to 105 minutes from now, inserted in reverse order.
            insertEvent("SCHEDULED", now.plus(Duration.ofMinutes(105 - i)), now.plus(days(3)));
        }

        Response response = get("/events", null);

        JsonNode items = response.body().path("items");
        assertThat(items.size()).isEqualTo(EventCatalogController.MAX_EVENTS);
        List<Instant> dropTimes = StreamSupport.stream(items.spliterator(), false)
                .map(item -> Instant.parse(item.path("dropAt").asText()))
                .toList();
        assertThat(dropTimes).isSorted();
        // The demo drop is 24 h away, so the 100 earliest are all inserted ones.
        assertThat(ids(response)).doesNotContain(DEMO_EVENT.toString());
    }

    // ---------------------------------------------------------------------------------------------
    // GET /events/{id}
    // ---------------------------------------------------------------------------------------------

    @Test
    void detailReturnsVenueTiersAndPricesWithoutAToken() {
        Response response = get("/events/" + DEMO_EVENT, null);

        assertThat(response.status()).isEqualTo(200);
        JsonNode body = response.body();
        assertThat(body.path("id").asText()).isEqualTo(DEMO_EVENT.toString());
        assertThat(body.path("title").asText()).isEqualTo("Stampede Demo Drop");
        assertThat(body.path("status").asText()).isEqualTo("SCHEDULED");
        assertThat(body.path("perUserLimit").asInt()).isEqualTo(4);
        assertThat(body.path("venue").path("name").asText()).isEqualTo("Stampede Demo Arena");
        assertThat(Instant.parse(body.path("dropAt").asText()))
                .isBefore(Instant.parse(body.path("startsAt").asText()));
        assertThat(body.has("strategy")).isFalse();
        assertServerTimeIsNow(response);

        // Most expensive first, prices in paise.
        JsonNode tiers = body.path("tiers");
        assertThat(tiers.size()).isEqualTo(3);
        assertTier(tiers.get(0), "PLATINUM", 450_000);
        assertTier(tiers.get(1), "GOLD", 250_000);
        assertTier(tiers.get(2), "SILVER", 120_000);
    }

    @Test
    void detailOfAnEventWithoutTiersHasAnEmptyTierList() {
        Instant now = Instant.now();
        UUID noTiers = insertEvent("SCHEDULED", now.plus(days(1)), now.plus(days(2)));

        Response response = get("/events/" + noTiers, null);

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body().path("tiers").isArray()).isTrue();
        assertThat(response.body().path("tiers").size()).isZero();
    }

    @Test
    void unknownIdIs404EventNotFound() {
        UUID unknown = UUID.randomUUID();

        Response response = get("/events/" + unknown, null);

        assertProblem(response, 404, "EVENT_NOT_FOUND");
        assertThat(response.body().path("instance").asText()).isEqualTo("/events/" + unknown);
    }

    @Test
    void draftEventIs404LikeAnUnknownOne() {
        Instant now = Instant.now();
        UUID draft = insertEvent("DRAFT", now.plus(days(1)), now.plus(days(2)));

        assertProblem(get("/events/" + draft, null), 404, "EVENT_NOT_FOUND");
    }

    @Test
    void malformedIdIs400ValidationFailed() {
        Response response = get("/events/not-a-uuid", null);

        assertProblem(response, 400, "VALIDATION_FAILED");
        assertThat(response.body().path("detail").asText()).isEqualTo("The value of 'id' is not valid.");
    }

    // ---------------------------------------------------------------------------------------------

    private UUID insertEvent(String status, Instant dropAt, Instant startsAt) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO events (id, venue_id, title, starts_at, drop_at, status, per_user_limit, strategy)
                VALUES (?, ?, ?, ?, ?, ?, 4, 'PESSIMISTIC')
                """)
                .params(id, DEMO_VENUE, "Test event " + status, startsAt.atOffset(ZoneOffset.UTC),
                        dropAt.atOffset(ZoneOffset.UTC), status)
                .update();
        extraEvents.add(id);
        return id;
    }

    private static Duration days(int days) {
        return Duration.ofDays(days);
    }

    private static JsonNode item(Response response, UUID id) {
        for (JsonNode item : response.body().path("items")) {
            if (item.path("id").asText().equals(id.toString())) {
                return item;
            }
        }
        return null;
    }

    private static List<String> ids(Response response) {
        return StreamSupport.stream(response.body().path("items").spliterator(), false)
                .map(item -> item.path("id").asText())
                .toList();
    }

    private static void assertTier(JsonNode tier, String name, long pricePaise) {
        assertThat(tier.path("name").asText()).isEqualTo(name);
        assertThat(tier.path("pricePaise").asLong()).isEqualTo(pricePaise);
        assertThat(tier.path("id").asText()).isEqualTo(SeedIds.tier("demo-drop", name).toString());
    }

    private static void assertServerTimeIsNow(Response response) {
        Instant serverTime = Instant.parse(response.body().path("serverTime").asText());
        assertThat(Duration.between(serverTime, Instant.now()).abs()).isLessThan(Duration.ofMinutes(1));
    }

    private static void assertProblem(Response response, int status, String code) {
        assertThat(response.status()).isEqualTo(status);
        assertThat(response.headers().getContentType().isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .isTrue();
        assertThat(response.code()).isEqualTo(code);
    }
}
