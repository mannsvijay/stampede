package com.stampede.inventory.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Loads seed files into a real Postgres.
 *
 * <p>Runs WITHOUT the usual test transaction ({@code NOT_SUPPORTED}): the loader's own
 * {@code @Transactional} must really commit or roll back, otherwise "writes nothing" would only prove
 * that the test's outer transaction hid the writes. Tables are emptied before each test instead.
 */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import(SeedLoader.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SeedLoaderTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    SeedLoader loader;

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void emptySeedTables() {
        jdbc.sql("TRUNCATE venues, sections, seats, events, price_tiers, event_seats CASCADE").update();
    }

    @Test
    void loadsFixtureVenueWithExpectedCounts() {
        SeedLoader.Result result = loader.load(venue("tiny-venue.json"), event("tiny-event.json"));

        assertCounts(1, 2, 18, 1, 2, 18);
        assertThat(result.seatsInserted()).isEqualTo(18);
        assertThat(result.eventSeatsInserted()).isEqualTo(18);
        assertThat(count("SELECT count(*) FROM event_seats WHERE status = 'AVAILABLE'")).isEqualTo(18);

        // Every event seat is priced by its section's default tier: section A (12 seats) is GOLD, B (6) SILVER.
        assertThat(count("""
                SELECT count(*) FROM event_seats es
                JOIN seats s ON s.id = es.seat_id JOIN sections sec ON sec.id = s.section_id
                JOIN price_tiers t ON t.id = es.tier_id
                WHERE sec.code = 'A' AND t.name = 'GOLD'""")).isEqualTo(12);
        assertThat(count("""
                SELECT count(*) FROM event_seats es
                JOIN seats s ON s.id = es.seat_id JOIN sections sec ON sec.id = s.section_id
                JOIN price_tiers t ON t.id = es.tier_id
                WHERE sec.code = 'B' AND t.name = 'SILVER'""")).isEqualTo(6);

        // Seat venue matches event venue.
        assertThat(count("""
                SELECT count(*) FROM event_seats es
                JOIN events e ON e.id = es.event_id JOIN seats s ON s.id = es.seat_id
                JOIN sections sec ON sec.id = s.section_id
                WHERE sec.venue_id <> e.venue_id""")).isZero();

        // Ids are the deterministic name-based ones.
        UUID seatA1 = SeedIds.seat("tiny-hall", "A", "A", 1);
        assertThat(jdbc.sql("SELECT x FROM seats WHERE id = ?").param(seatA1).query(Double.class).single())
                .isEqualTo(10.0);
        assertThat(jdbc.sql("SELECT attrs FROM seats WHERE id = ?").param(seatA1).query(String.class).single())
                .isEqualTo("{\"aisle\": true, \"accessible\": false}");
    }

    @Test
    void loadingTwiceGivesSameCounts() {
        loader.load(venue("tiny-venue.json"), event("tiny-event.json"));
        SeedLoader.Result second = loader.load(venue("tiny-venue.json"), event("tiny-event.json"));

        assertCounts(1, 2, 18, 1, 2, 18);
        assertThat(second.seatsInserted()).isZero();
        assertThat(second.eventSeatsInserted()).isZero();
    }

    @Test
    void duplicateSeatIsRejectedAndWritesNothing() {
        assertThatThrownBy(() -> loader.load(venue("tiny-venue-duplicate-seat.json"), event("tiny-event.json")))
                .isInstanceOf(SeedException.class)
                .hasMessageContaining("duplicate seat: section A, row B, seat 2")
                .hasMessageContaining("nothing was written");

        assertCounts(0, 0, 0, 0, 0, 0);
    }

    @Test
    void unknownDefaultTierIsRejectedAndWritesNothing() {
        assertThatThrownBy(() -> loader.load(venue("tiny-venue.json"), event("tiny-event-missing-tier.json")))
                .isInstanceOf(SeedException.class)
                .hasMessageContaining("section B: defaultTier 'SILVER' is not one of the event's tiers [GOLD]");

        assertCounts(0, 0, 0, 0, 0, 0);
    }

    @Test
    void changedLayoutForLoadedVenueIsRejectedAndRolledBack() {
        loader.load(venue("tiny-venue.json"), event("tiny-event.json"));

        // Same venue slug, but seat A/A/1 has moved from x=10.0 to x=11.0.
        assertThatThrownBy(() -> loader.load(venue("tiny-venue-moved-seat.json"), event("tiny-event.json")))
                .isInstanceOf(SeedException.class)
                .hasMessageContaining("seats differ")
                .hasMessageContaining("A/A/1 at (11.0, 15.0)")
                .hasMessageContaining("docker compose down -v");

        assertCounts(1, 2, 18, 1, 2, 18);
        UUID seatA1 = SeedIds.seat("tiny-hall", "A", "A", 1);
        assertThat(jdbc.sql("SELECT x FROM seats WHERE id = ?").param(seatA1).query(Double.class).single())
                .isEqualTo(10.0);
    }

    @Test
    void committedDemoVenueLoadsAndHasAisleAndAccessibleSeats() {
        VenueFile demo = SeedFiles.readVenue(new ClassPathResource("seed/venues/demo-arena.json"));
        EventSeedFile drop = SeedFiles.readEvent(new ClassPathResource("seed/events/demo-drop.json"));

        loader.load(demo, drop);

        assertThat(demo.seatCount()).isEqualTo(2014);
        assertCounts(1, 10, 2014, 1, 3, 2014);
        assertThat(count("SELECT count(*) FROM seats WHERE (attrs ->> 'aisle')::boolean")).isEqualTo(214);
        assertThat(count("SELECT count(*) FROM seats WHERE (attrs ->> 'accessible')::boolean")).isEqualTo(40);
        assertThat(count("SELECT count(*) FROM event_seats WHERE status = 'AVAILABLE'")).isEqualTo(2014);
    }

    // ---------------------------------------------------------------------------------------------

    private void assertCounts(int venues, int sections, int seats, int events, int tiers, int eventSeats) {
        assertThat(count("SELECT count(*) FROM venues")).as("venues").isEqualTo(venues);
        assertThat(count("SELECT count(*) FROM sections")).as("sections").isEqualTo(sections);
        assertThat(count("SELECT count(*) FROM seats")).as("seats").isEqualTo(seats);
        assertThat(count("SELECT count(*) FROM events")).as("events").isEqualTo(events);
        assertThat(count("SELECT count(*) FROM price_tiers")).as("price_tiers").isEqualTo(tiers);
        assertThat(count("SELECT count(*) FROM event_seats")).as("event_seats").isEqualTo(eventSeats);
    }

    private int count(String sql) {
        return jdbc.sql(sql).query(Integer.class).single();
    }

    private static VenueFile venue(String name) {
        return SeedFiles.readVenue(new ClassPathResource("seed/" + name));
    }

    private static EventSeedFile event(String name) {
        return SeedFiles.readEvent(new ClassPathResource("seed/" + name));
    }
}
