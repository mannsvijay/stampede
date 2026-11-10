package com.stampede.inventory.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.stampede.inventory.strategy.ConcurrencyHarness.Config;
import com.stampede.inventory.strategy.ConcurrencyHarness.Fixture;
import com.stampede.inventory.strategy.ConcurrencyHarness.Scenario;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * M1.3: the concurrency harness against the pessimistic strategy. 1000 rounds per scenario by default;
 * CI passes {@code -Dstampede.harness.rounds=100}. Reports land in build/reports/concurrency/.
 * <p>
 * 16 threads with a pool of 20: every racing thread gets its own connection, so all contention happens
 * in Postgres (not in the pool's queue), and the round's own reset and checks never wait for one.
 */
@JdbcTest(properties = "spring.datasource.hikari.maximum-pool-size=" + PessimisticConcurrencyTest.POOL_SIZE)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import(PessimisticInventoryStrategy.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PessimisticConcurrencyTest {

    static final int THREADS = 16;
    static final int POOL_SIZE = THREADS + 4;

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    static final ExecutorService RACERS = Executors.newFixedThreadPool(THREADS);

    static final UUID USER = UUID.fromString("00000000-0000-4000-8000-0000000000a1");
    static final UUID VENUE = UUID.fromString("00000000-0000-4000-8000-000000000001");
    static final UUID SECTION = UUID.fromString("00000000-0000-4000-8000-000000000002");
    static final UUID EVENT = UUID.fromString("00000000-0000-4000-8000-00000000000a");
    static final UUID TIER = UUID.fromString("00000000-0000-4000-8000-0000000000ca");
    // Half of these start with a hex digit >= 8, so Java and Postgres disagree on their order (D-015).
    static final List<UUID> SEATS = List.of(
            UUID.fromString("10000000-0000-4000-8000-000000000001"),
            UUID.fromString("90000000-0000-4000-8000-000000000002"),
            UUID.fromString("30000000-0000-4000-8000-000000000003"),
            UUID.fromString("c0000000-0000-4000-8000-000000000004"),
            UUID.fromString("50000000-0000-4000-8000-000000000005"),
            UUID.fromString("e0000000-0000-4000-8000-000000000006"));
    static final Fixture FIXTURE = new Fixture(EVENT, USER, SEATS);

    @Autowired
    PessimisticInventoryStrategy strategy;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    DataSource dataSource;

    @AfterAll
    static void stopThreads() {
        RACERS.shutdownNow();
    }

    @BeforeEach
    void insertFixture() {
        assertThat(((HikariDataSource) dataSource).getMaximumPoolSize()).as("pool size").isEqualTo(POOL_SIZE);

        jdbc.sql("TRUNCATE users, venues CASCADE").update();
        jdbc.sql("INSERT INTO users (id, email, password_hash) VALUES (?, 'racer@example.test', 'x')").param(USER).update();
        jdbc.sql("INSERT INTO venues (id, name, layout_json) VALUES (?, 'Race venue', '{}')").param(VENUE).update();
        jdbc.sql("INSERT INTO sections (id, venue_id, code, default_tier) VALUES (?, ?, 'A', 'GOLD')")
                .params(SECTION, VENUE).update();
        jdbc.sql("""
                INSERT INTO events (id, venue_id, title, starts_at, drop_at, status, per_user_limit, strategy)
                VALUES (?, ?, 'Race event', now() + interval '30 days', now() - interval '1 hour',
                        'LIVE', 4, 'PESSIMISTIC')
                """).params(EVENT, VENUE).update();
        jdbc.sql("INSERT INTO price_tiers (id, event_id, name, price_paise) VALUES (?, ?, 'GOLD', 150000)")
                .params(TIER, EVENT).update();
        int number = 1;
        for (UUID seat : SEATS) {
            jdbc.sql("INSERT INTO seats (id, section_id, row_label, number, x, y) VALUES (?, ?, 'A', ?, 0, 0)")
                    .params(seat, SECTION, number++).update();
            jdbc.sql("INSERT INTO event_seats (event_id, seat_id, tier_id, status) VALUES (?, ?, ?, 'AVAILABLE')")
                    .params(EVENT, seat, TIER).update();
        }
    }

    @Test
    void singleSeat() {
        run(HarnessScenarios.singleSeat());
    }

    @Test
    void overlappingSets() {
        run(HarnessScenarios.overlappingSets());
    }

    @Test
    void churn() {
        run(HarnessScenarios.churn());
    }

    @Test
    void capRace() {
        run(HarnessScenarios.capRace());
    }

    private void run(Scenario scenario) {
        ConcurrencyHarness harness = new ConcurrencyHarness(strategy, jdbc, FIXTURE,
                Config.fromSystemProperties(THREADS), RACERS);

        HarnessReport report = harness.run(scenario);

        report.writeTo(Path.of("build", "reports", "concurrency"));
        System.out.println(report.render());
        assertThat(report.failures()).as("%n%s", report.render()).isEmpty();
    }
}
