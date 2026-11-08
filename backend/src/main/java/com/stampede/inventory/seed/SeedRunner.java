package com.stampede.inventory.seed;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

/**
 * Seeds the demo venue and event at startup when the {@code seed} profile is active, then lets the app
 * keep serving. Safe on every boot because the loader is idempotent.
 *
 * <p>Run: {@code cd backend && ./gradlew bootRun --args='--spring.profiles.active=seed'}
 *
 * <p>Flyway runs before any ApplicationRunner, so the schema always exists by the time this runs. If
 * seeding fails, the exception stops the application from starting, so a bad seed can't go unnoticed.
 */
@Component
@Profile("seed")
@Order(SeedRunner.ORDER)
class SeedRunner implements ApplicationRunner {

    /** Runs before {@link DemoRescheduleRunner}, which needs the seeded event to exist. */
    static final int ORDER = 100;

    private static final Logger log = LoggerFactory.getLogger(SeedRunner.class);

    private final SeedLoader loader;
    private final Resource venueFile;
    private final Resource eventFile;

    SeedRunner(SeedLoader loader,
               @Value("${stampede.seed.venue:classpath:seed/venues/demo-arena.json}") Resource venueFile,
               @Value("${stampede.seed.event:classpath:seed/events/demo-drop.json}") Resource eventFile) {
        this.loader = loader;
        this.venueFile = venueFile;
        this.eventFile = eventFile;
    }

    @Override
    public void run(ApplicationArguments args) {
        SeedLoader.Result result = loader.load(SeedFiles.readVenue(venueFile), SeedFiles.readEvent(eventFile));
        log.info("Seed complete: venue {} ({} seats, {} newly inserted), event {} ({} event seats newly inserted)",
                result.venueId(), result.seats(), result.seatsInserted(), result.eventId(),
                result.eventSeatsInserted());
    }
}
