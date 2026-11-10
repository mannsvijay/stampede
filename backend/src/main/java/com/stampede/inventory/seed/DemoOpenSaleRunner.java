package com.stampede.inventory.seed;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
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
 * Dev only: opens the demo event's sale now, so seats can be held locally (D-016). Holds are refused
 * before the drop (SALE_NOT_OPEN), and {@code demo-reschedule} always puts the drop in the future.
 *
 * <p>Run with the {@code demo-open-sale} profile, usually as {@code seed,demo-open-sale}. It sets the
 * drop to one minute ago and the start to the seed file's offset from now, so the event is on sale
 * and still listed as upcoming. It runs after {@code demo-reschedule} if both are active, and wins.
 * Like that runner, it only touches a SCHEDULED event and only these two timestamps.
 */
@Component
@Profile("demo-open-sale")
@Order(SeedRunner.ORDER + 2)
class DemoOpenSaleRunner implements ApplicationRunner {

    static final Duration DROPPED_AGO = Duration.ofMinutes(1);

    private static final Logger log = LoggerFactory.getLogger(DemoOpenSaleRunner.class);

    private final JdbcClient jdbc;
    private final Resource eventFile;

    DemoOpenSaleRunner(JdbcClient jdbc,
                       @Value("${stampede.seed.event:classpath:seed/events/demo-drop.json}") Resource eventFile) {
        this.jdbc = jdbc;
        this.eventFile = eventFile;
    }

    @Override
    public void run(ApplicationArguments args) {
        openSale(Instant.now());
    }

    /** Returns true if the event's sale was opened. */
    boolean openSale(Instant now) {
        EventSeedFile event = SeedFiles.readEvent(eventFile);
        UUID eventId = SeedIds.event(event.slug());
        Instant dropAt = now.minus(DROPPED_AGO);
        Instant startsAt = now.plus(Duration.ofDays(event.startsInDays()));

        int updated = jdbc.sql("""
                UPDATE events SET drop_at = ?, starts_at = ?
                WHERE id = ? AND status = 'SCHEDULED'
                """)
                .params(dropAt.atOffset(ZoneOffset.UTC), startsAt.atOffset(ZoneOffset.UTC), eventId)
                .update();

        if (updated == 1) {
            log.info("Opened the demo sale '{}' ({}): dropped at {}, starts at {}",
                    event.slug(), eventId, dropAt, startsAt);
            return true;
        }
        log.warn("Demo event '{}' ({}) is missing or not SCHEDULED; its sale was not opened. "
                + "Run with the seed profile first.", event.slug(), eventId);
        return false;
    }
}
