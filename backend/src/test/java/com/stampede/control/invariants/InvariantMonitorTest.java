package com.stampede.control.invariants;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The scheduled run (M1.7, D-019): grace period, open events only, and the per-rule gauge. Each test
 * runs in the rolled-back test transaction, which the checker joins, so the stuck order it builds
 * is never committed.
 */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import({InvariantChecker.class, InvariantMonitor.class, InvariantMonitorTest.Meters.class})
class InvariantMonitorTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @TestConfiguration(proxyBeanMethods = false)
    static class Meters {
        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }

    static final UUID USER = UUID.fromString("00000000-0000-4000-8000-0000000000a1");
    static final UUID VENUE = UUID.fromString("00000000-0000-4000-8000-000000000001");
    static final UUID SECTION = UUID.fromString("00000000-0000-4000-8000-000000000002");
    static final UUID EVENT = UUID.fromString("00000000-0000-4000-8000-00000000000e");
    static final UUID TIER = UUID.fromString("00000000-0000-4000-8000-0000000000c1");
    static final UUID SEAT = UUID.fromString("10000000-0000-4000-8000-000000000001");
    static final UUID HOLD = UUID.fromString("00000000-0000-4000-8000-0000000001a1");
    static final UUID ORDER = UUID.fromString("00000000-0000-4000-8000-0000000002b1");

    @Autowired
    InvariantMonitor monitor;

    @Autowired
    InvariantChecker checker;

    @Autowired
    MeterRegistry meters;

    @Autowired
    JdbcClient jdbc;

    /** A checkout between steps 1 and 3 (D-017): hold ACTIVE, seat HELD, order PENDING, created now. */
    @BeforeEach
    void insertCheckoutInFlight() {
        jdbc.sql("TRUNCATE users, venues CASCADE").update();
        jdbc.sql("INSERT INTO users (id, email, password_hash) VALUES (?, 'buyer@example.test', 'x')").param(USER).update();
        jdbc.sql("INSERT INTO venues (id, name, layout_json) VALUES (?, 'Venue', '{}')").param(VENUE).update();
        jdbc.sql("INSERT INTO sections (id, venue_id, code, default_tier) VALUES (?, ?, 'A', 'GOLD')")
                .params(SECTION, VENUE).update();
        jdbc.sql("INSERT INTO seats (id, section_id, row_label, number, x, y) VALUES (?, ?, 'A', 1, 0, 0)")
                .params(SEAT, SECTION).update();
        jdbc.sql("""
                INSERT INTO events (id, venue_id, title, starts_at, drop_at, status, per_user_limit, strategy)
                VALUES (?, ?, 'Event', now() + interval '30 days', now() - interval '1 hour', 'LIVE', 4, 'PESSIMISTIC')
                """).params(EVENT, VENUE).update();
        jdbc.sql("INSERT INTO price_tiers (id, event_id, name, price_paise) VALUES (?, ?, 'GOLD', 150000)")
                .params(TIER, EVENT).update();
        jdbc.sql("""
                INSERT INTO holds (id, event_id, user_id, seat_ids, expires_at, status)
                VALUES (?, ?, ?, ?, now() + interval '5 minutes', 'ACTIVE')
                """).params(HOLD, EVENT, USER, new UUID[] {SEAT}).update();
        jdbc.sql("INSERT INTO event_seats (event_id, seat_id, tier_id, status, hold_id) VALUES (?, ?, ?, 'HELD', ?)")
                .params(EVENT, SEAT, TIER, HOLD).update();
        jdbc.sql("INSERT INTO user_event_allowance (event_id, user_id, used) VALUES (?, ?, 1)").params(EVENT, USER).update();
        jdbc.sql("""
                INSERT INTO orders (id, user_id, event_id, status, total_paise, saga_state, hold_id)
                VALUES (?, ?, ?, 'PENDING', 150000, 'ORDER_CREATED', ?)
                """).params(ORDER, USER, EVENT, HOLD).update();
        jdbc.sql("INSERT INTO order_items (order_id, event_id, seat_id, price_paise) VALUES (?, ?, ?, 150000)")
                .params(ORDER, EVENT, SEAT).update();
    }

    @Test
    void aCheckoutStillWithinTheGracePeriodIsNotReported() {
        assertThat(monitor.checkOnce()).isEmpty();
        assertThat(monitor.current("O7")).isZero();
    }

    @Test
    void aStuckCheckoutIsReportedAndCountedThenClearedOnceFixed() {
        jdbc.sql("UPDATE orders SET created_at = now() - interval '10 minutes' WHERE id = ?").param(ORDER).update();

        assertThat(monitor.checkOnce()).extracting(Violation::ruleId).containsExactly("O7");
        assertThat(monitor.current("O7")).isEqualTo(1);
        assertThat(meters.get("stampede.invariants.violations").tag("rule", "O7").gauge().value()).isEqualTo(1.0);

        jdbc.sql("UPDATE orders SET status = 'CANCELLED', saga_state = 'ORDER_CANCELLED' WHERE id = ?").param(ORDER).update();
        jdbc.sql("UPDATE order_items SET active = false WHERE order_id = ?").param(ORDER).update();
        assertThat(monitor.checkOnce()).isEmpty();
        assertThat(monitor.current("O7")).as("the gauge is the latest count, not a running total").isZero();
    }

    @Test
    void endedAndDraftEventsAreNotChecked() {
        jdbc.sql("UPDATE orders SET created_at = now() - interval '10 minutes' WHERE id = ?").param(ORDER).update();
        jdbc.sql("UPDATE events SET status = 'ENDED' WHERE id = ?").param(EVENT).update();
        assertThat(monitor.checkOnce()).isEmpty();
        jdbc.sql("UPDATE events SET status = 'DRAFT' WHERE id = ?").param(EVENT).update();
        assertThat(monitor.checkOnce()).isEmpty();
    }

    @Test
    void everyRuleHasAGauge() {
        assertThat(meters.find("stampede.invariants.violations").gauges()).hasSize(checker.ruleIds().size());
    }

    @Test
    void aZeroGracePeriodIsRefused() {
        // Strict would report every checkout in flight as broken.
        assertThatThrownBy(() -> new InvariantMonitor(checker, new SimpleMeterRegistry(), Duration.ZERO))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be positive");
    }
}
