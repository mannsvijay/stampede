package com.stampede.inventory.seed;

import com.stampede.testsupport.TestDatabases;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The dev-only demo-open-sale profile's runner (D-016), against the tiny fixture event (drop in 1 h,
 * starts in 2 days). Each test runs in a transaction that is rolled back afterwards.
 */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(SeedLoader.class)
class DemoOpenSaleRunnerTest {

    /** Its own database on the shared test Postgres (TestDatabases): nothing else ever sees its data. */
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabases.register(registry, "demo_open_sale_runner");
    }

    private static final UUID EVENT = SeedIds.event("tiny-show");

    @Autowired
    SeedLoader loader;

    @Autowired
    JdbcClient jdbc;

    private DemoOpenSaleRunner runner() {
        return new DemoOpenSaleRunner(jdbc, new ClassPathResource("seed/tiny-event.json"));
    }

    private void seed() {
        loader.load(SeedFiles.readVenue(new ClassPathResource("seed/tiny-venue.json")),
                SeedFiles.readEvent(new ClassPathResource("seed/tiny-event.json")));
    }

    @Test
    void putsTheDropJustInThePastAndKeepsTheEventUpcoming() {
        seed();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);  // Postgres stores microseconds

        assertThat(runner().openSale(now)).isTrue();

        assertThat(time("drop_at")).isEqualTo(now.minus(Duration.ofMinutes(1)));
        assertThat(time("starts_at")).isEqualTo(now.plus(Duration.ofDays(2)));
        // This is the rule the hold service applies (SALE_NOT_OPEN otherwise).
        assertThat(jdbc.sql("SELECT drop_at <= now() AND status = 'SCHEDULED' FROM events WHERE id = ?")
                .param(EVENT).query(Boolean.class).single()).isTrue();
        // Seeding again still passes: the loader's file check doesn't compare times.
        seed();
    }

    @Test
    void worksEvenWhenTheStoredStartIsAlreadyInThePast() {
        // Without moving starts_at too, drop_at <= starts_at (ck_events_drop_before_start) would fail.
        seed();
        jdbc.sql("UPDATE events SET drop_at = now() - interval '3 days', starts_at = now() - interval '2 days' "
                + "WHERE id = ?").param(EVENT).update();

        assertThat(runner().openSale(Instant.now())).isTrue();
        assertThat(time("starts_at")).isAfter(Instant.now());
    }

    @Test
    void leavesAnEventThatIsNoLongerScheduledUnchanged() {
        seed();
        jdbc.sql("UPDATE events SET status = 'LIVE' WHERE id = ?").param(EVENT).update();
        Instant dropBefore = time("drop_at");

        assertThat(runner().openSale(Instant.now())).isFalse();

        assertThat(time("drop_at")).isEqualTo(dropBefore);
    }

    @Test
    void doesNothingWhenTheEventWasNeverSeeded() {
        jdbc.sql("TRUNCATE venues, sections, seats, events, price_tiers, event_seats CASCADE").update();

        assertThat(runner().openSale(Instant.now())).isFalse();
    }

    private Instant time(String column) {
        return jdbc.sql("SELECT " + column + " FROM events WHERE id = ?").param(EVENT)
                .query(OffsetDateTime.class).single().toInstant();
    }
}
