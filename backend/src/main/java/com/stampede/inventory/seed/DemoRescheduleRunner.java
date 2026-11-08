package com.stampede.inventory.seed;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Dev only: moves the demo event's drop and start times back into the future, measured from now with
 * the same offsets as the seed file. The seed loader sets them only on the first load, so after a day
 * the board would show a drop in the past.
 *
 * <p>Run with the {@code demo-reschedule} profile, usually together with {@code seed}; it runs after
 * the seed runner. Only a SCHEDULED event is moved: once a drop is LIVE or later, its times are part
 * of what happened. Safe because no other row refers to these two timestamps, and the seed loader's
 * check that the database matches the files doesn't compare them.
 */
@Component
@Profile("demo-reschedule")
@Order(SeedRunner.ORDER + 1)
class DemoRescheduleRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoRescheduleRunner.class);

    private final JdbcClient jdbc;
    private final Resource eventFile;

    DemoRescheduleRunner(JdbcClient jdbc,
                         @Value("${stampede.seed.event:classpath:seed/events/demo-drop.json}") Resource eventFile) {
        this.jdbc = jdbc;
        this.eventFile = eventFile;
    }

    @Override
    public void run(ApplicationArguments args) {
        reschedule(Instant.now());
    }

    /** Returns true if the event was moved. */
    boolean reschedule(Instant now) {
        EventSeedFile event = SeedFiles.readEvent(eventFile);
        UUID eventId = SeedIds.event(event.slug());
        Instant dropAt = now.plus(Duration.ofHours(event.dropInHours()));
        Instant startsAt = now.plus(Duration.ofDays(event.startsInDays()));

        int updated = jdbc.sql("""
                UPDATE events SET drop_at = ?, starts_at = ?
                WHERE id = ? AND status = 'SCHEDULED'
                """)
                .params(dropAt.atOffset(ZoneOffset.UTC), startsAt.atOffset(ZoneOffset.UTC), eventId)
                .update();

        if (updated == 1) {
            log.info("Rescheduled demo event '{}' ({}): drop at {}, starts at {}",
                    event.slug(), eventId, dropAt, startsAt);
            return true;
        }
        Optional<String> status = jdbc.sql("SELECT status FROM events WHERE id = ?")
                .param(eventId).query(String.class).optional();
        if (status.isEmpty()) {
            log.warn("Demo event '{}' ({}) not found; run with the seed profile first.", event.slug(), eventId);
        } else {
            log.warn("Demo event '{}' is {}, not SCHEDULED; its times were left unchanged.",
                    event.slug(), status.get());
        }
        return false;
    }
}
