package com.stampede.inventory.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.stampede.control.invariants.InvariantChecker;
import com.stampede.inventory.strategy.ConcurrencyHarness.Config;
import com.stampede.inventory.strategy.ConcurrencyHarness.Fixture;
import com.stampede.inventory.strategy.ConcurrencyHarness.Scenario;
import com.stampede.testsupport.TestDatabases;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The concurrency harness (M1.3; all three strategies since M2.3b): four scenarios, each run once per
 * strategy ({@code singleSeat[PESSIMISTIC]}, ...). {@code -Dstampede.harness.strategies} picks the
 * strategies ({@link HarnessStrategies}; all by default). 100 rounds per scenario by default (as in CI);
 * the full proof passes {@code -Dstampede.harness.rounds=1000}. Reports land in
 * build/reports/concurrency/{@code <STRATEGY>-<scenario>.txt}.
 * <p>
 * 16 threads with a pool of 20: every racing thread gets its own connection, so all contention happens
 * in Postgres (not in the pool's queue), and the round's own reset and checks never wait for one. The
 * pool keeps all 20 open (minimum-idle), overriding the tests' shrink-to-zero default, so no thread
 * waits for a new connection at the start gate.
 * <p>
 * <b>One context for all three strategies</b>, with the Redis beans: its own database and key prefix
 * ({@code strategy_concurrency}), checked before every test by {@link #ownStores()}. The Postgres
 * strategies never touch Redis, and I10 skips events that aren't REDIS_FIRST, so Redis keys left by an
 * earlier REDIS_FIRST test are inert; every REDIS_FIRST round starts from an empty prefix with the event
 * freshly loaded and checks after the stream is drained ({@link RedisRoundHooks}), so I4-I7 and the
 * invariant checker, I10 included, hold strictly (D-022).
 */
@JdbcTest(properties = {
        "spring.datasource.hikari.maximum-pool-size=" + StrategyConcurrencyTest.POOL_SIZE,
        "spring.datasource.hikari.minimum-idle=" + StrategyConcurrencyTest.POOL_SIZE})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(RedisAutoConfiguration.class)
@Import({StrategyTestConfig.class, InvariantChecker.class,
        RedisHoldStore.class, RedisStreamMetrics.class, RedisHoldWriter.class, RedisEventLoader.class,
        RedisStreamConsumer.class, RedisCatchUp.class, RedisFirstInventoryStrategy.class, RedisHoldStateReader.class})
// The drain reads with a 10 ms block, so an empty stream doesn't cost a second per round.
@TestPropertySource(properties = {
        "stampede.inventory.redis.consumer.block=10ms",
        "stampede.inventory.redis.consumer.claim-min-idle=0s"})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class StrategyConcurrencyTest {

    static final String STORES = "strategy_concurrency";
    static final int THREADS = 16;
    static final int POOL_SIZE = THREADS + 4;

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

    /** Its own database and key prefix on the shared test servers. */
    @DynamicPropertySource
    static void stores(DynamicPropertyRegistry registry) {
        TestDatabases.register(registry, STORES);
        TestDatabases.registerRedis(registry, STORES);
    }

    static List<StrategyKind> strategies() {
        return HarnessStrategies.selected();
    }

    @Autowired
    JdbcClient jdbc;

    @Autowired
    InvariantChecker invariants;

    @Autowired
    DataSource dataSource;

    @Autowired
    MeterRegistry meters;

    @Autowired
    PessimisticInventoryStrategy pessimistic;

    @Autowired
    OptimisticInventoryStrategy optimistic;

    @Autowired
    RedisFirstInventoryStrategy redisFirst;

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    RedisHoldStore store;

    @Autowired
    RedisStreamConsumer consumer;

    @Autowired
    RedisEventLoader loader;

    private ExecutorService racers;

    @BeforeEach
    void ownStores() {
        assertThat(((HikariDataSource) dataSource).getMaximumPoolSize()).as("pool size").isEqualTo(POOL_SIZE);
        assertThat(((HikariDataSource) dataSource).getJdbcUrl()).as("own database").endsWith("/" + STORES);
        assertThat(store.keys().all()).as("own key prefix").isEqualTo(STORES + ":");
        racers = Executors.newFixedThreadPool(THREADS);
    }

    @AfterEach
    void stopThreads() {
        racers.shutdownNow();
    }

    @ParameterizedTest(name = "singleSeat[{0}]")
    @MethodSource("strategies")
    void singleSeat(StrategyKind kind) {
        run(kind, HarnessScenarios.singleSeat());
    }

    @ParameterizedTest(name = "overlappingSets[{0}]")
    @MethodSource("strategies")
    void overlappingSets(StrategyKind kind) {
        run(kind, HarnessScenarios.overlappingSets());
    }

    @ParameterizedTest(name = "churn[{0}]")
    @MethodSource("strategies")
    void churn(StrategyKind kind) {
        run(kind, HarnessScenarios.churn());
    }

    @ParameterizedTest(name = "capRace[{0}]")
    @MethodSource("strategies")
    void capRace(StrategyKind kind) {
        run(kind, HarnessScenarios.capRace());
    }

    private InventoryStrategy strategy(StrategyKind kind) {
        InventoryStrategy strategy = switch (kind) {
            case PESSIMISTIC -> pessimistic;
            case OPTIMISTIC -> optimistic;
            case REDIS_FIRST -> redisFirst;
        };
        assertThat(strategy.kind()).as("bean for %s", kind).isEqualTo(kind);
        return strategy;
    }

    /** Redis-first wipes and reloads its keys each round and drains the stream before the checks. */
    private ConcurrencyHarness.RoundHooks roundHooks(StrategyKind kind) {
        return kind == StrategyKind.REDIS_FIRST
                ? new RedisRoundHooks(redis, STORES, consumer, loader, EVENT)
                : ConcurrencyHarness.RoundHooks.NONE;
    }

    private void run(StrategyKind kind, Scenario scenario) {
        InventoryStrategy strategy = strategy(kind);
        insertFixture(kind);
        ConcurrencyHarness harness = new ConcurrencyHarness(strategy, jdbc, invariants, FIXTURE,
                Config.fromSystemProperties(THREADS), racers, roundHooks(kind));
        double attempts = count(HoldMetrics.ATTEMPTS, kind);
        double conflicts = count(HoldMetrics.CONFLICTS, kind);

        HarnessReport report = harness.run(scenario);

        report.writeTo(Path.of("build", "reports", "concurrency"));
        System.out.println(report.render());
        System.out.printf("%s %s: %.0f hold tries, %.0f version conflicts (setup holds included)%n",
                kind, scenario.name(), count(HoldMetrics.ATTEMPTS, kind) - attempts,
                count(HoldMetrics.CONFLICTS, kind) - conflicts);
        assertThat(report.failures()).as("%n%s", report.render()).isEmpty();
    }

    /** The event, with {@code kind} as its strategy, and its six seats. */
    private void insertFixture(StrategyKind kind) {
        jdbc.sql("TRUNCATE users, venues CASCADE").update();
        jdbc.sql("INSERT INTO users (id, email, password_hash) VALUES (?, 'racer@example.test', 'x')").param(USER).update();
        jdbc.sql("INSERT INTO venues (id, name, layout_json) VALUES (?, 'Race venue', '{}')").param(VENUE).update();
        jdbc.sql("INSERT INTO sections (id, venue_id, code, default_tier) VALUES (?, ?, 'A', 'GOLD')")
                .params(SECTION, VENUE).update();
        jdbc.sql("""
                INSERT INTO events (id, venue_id, title, starts_at, drop_at, status, per_user_limit, strategy)
                VALUES (?, ?, 'Race event', now() + interval '30 days', now() - interval '1 hour',
                        'LIVE', 4, ?)
                """).params(EVENT, VENUE, kind.name()).update();
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

    private double count(String name, StrategyKind kind) {
        Counter counter = meters.find(name).tag("strategy", kind.name()).counter();
        return counter == null ? 0 : counter.count();
    }
}
