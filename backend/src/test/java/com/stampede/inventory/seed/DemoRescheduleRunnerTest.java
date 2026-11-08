package com.stampede.inventory.seed;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The dev-only demo-reschedule profile's runner, against the tiny fixture event (drop in 1 h,
 * starts in 2 days). Each test runs in a transaction that is rolled back afterwards.
 */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import(SeedLoader.class)
class DemoRescheduleRunnerTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final UUID EVENT = SeedIds.event("tiny-show");

    @Autowired
    SeedLoader loader;

    @Autowired
    JdbcClient jdbc;

    private DemoRescheduleRunner runner() {
        return new DemoRescheduleRunner(jdbc, new ClassPathResource("seed/tiny-event.json"));
    }

    private void seed() {
        loader.load(SeedFiles.readVenue(new ClassPathResource("seed/tiny-venue.json")),
                SeedFiles.readEvent(new ClassPathResource("seed/tiny-event.json")));
    }

    @Test
    void movesAPastScheduledDropBackIntoTheFuture() {
        seed();
        jdbc.sql("UPDATE events SET drop_at = now() - interval '3 days', starts_at = now() - interval '2 days' "
                + "WHERE id = ?").param(EVENT).update();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);  // Postgres stores microseconds

        assertThat(runner().reschedule(now)).isTrue();

        assertThat(time("drop_at")).isEqualTo(now.plus(Duration.ofHours(1)));
        assertThat(time("starts_at")).isEqualTo(now.plus(Duration.ofDays(2)));
        // Seeding again still passes: the loader's file check doesn't compare times.
        seed();
    }

    @Test
    void leavesAnEventThatIsNoLongerScheduledUnchanged() {
        seed();
        jdbc.sql("UPDATE events SET status = 'LIVE' WHERE id = ?").param(EVENT).update();
        Instant dropBefore = time("drop_at");
        Instant startsBefore = time("starts_at");

        assertThat(runner().reschedule(Instant.now().plus(Duration.ofDays(30)))).isFalse();

        assertThat(time("drop_at")).isEqualTo(dropBefore);
        assertThat(time("starts_at")).isEqualTo(startsBefore);
    }

    @Test
    void doesNothingWhenTheEventWasNeverSeeded() {
        jdbc.sql("TRUNCATE venues, sections, seats, events, price_tiers, event_seats CASCADE").update();

        assertThat(runner().reschedule(Instant.now())).isFalse();
    }

    private Instant time(String column) {
        return jdbc.sql("SELECT " + column + " FROM events WHERE id = ?").param(EVENT)
                .query(OffsetDateTime.class).single().toInstant();
    }
}
