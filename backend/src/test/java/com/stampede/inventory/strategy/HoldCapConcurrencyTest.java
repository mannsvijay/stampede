package com.stampede.inventory.strategy;

import static com.stampede.inventory.strategy.ConcurrencyHarness.uuid;
import static org.assertj.core.api.Assertions.assertThat;

import com.stampede.control.invariants.InvariantChecker;
import com.stampede.inventory.strategy.ConcurrencyHarness.Action;
import com.stampede.inventory.strategy.ConcurrencyHarness.CallResult;
import com.stampede.inventory.strategy.ConcurrencyHarness.Config;
import com.stampede.inventory.strategy.ConcurrencyHarness.Fixture;
import com.stampede.inventory.strategy.ConcurrencyHarness.RoundPlan;
import com.stampede.inventory.strategy.ConcurrencyHarness.Scenario;
import com.stampede.inventory.strategy.HoldOutcome.Held;
import com.stampede.inventory.strategy.HoldOutcome.Rejected;
import com.stampede.testsupport.TestDatabases;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
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
 * The per-user cap alone under concurrency (M1.4, D-005; all three strategies since M2.3b). One user,
 * 16 threads, and every thread asks for its OWN seats (no two threads share a seat), so seat collisions
 * can't happen and only the cap decides. Runs on the M1.3 harness, so every round also gets I1-I8 (I8:
 * the counter equals the seats of the user's active holds and is never over the limit) and I7 (no
 * exceptions, so no deadlocks). Each scenario runs once per strategy
 * ({@code parallelHoldsByOneUserNeverExceedTheLimit[PESSIMISTIC]}, ...): CLAUDE.md requires the cap under
 * concurrency in every strategy, Lua included (Redis-first's {@code hold.lua} recounts the user's live
 * holds, D-022). Rounds: {@code -Dstampede.harness.rounds} (default 100); strategies:
 * {@code -Dstampede.harness.strategies} ({@link HarnessStrategies}; all by default).
 * <p>
 * <b>One context for all three strategies</b>, with the Redis beans: its own database and key prefix
 * ({@code hold_cap_concurrency}), checked before every test by {@link #ownStores()}. Redis-first rounds
 * start from an empty prefix and end with the stream drained ({@link RedisRoundHooks}); see
 * {@link StrategyConcurrencyTest} for why Redis keys left by an earlier test are inert.
 */
// The pool keeps all its connections open (minimum-idle), as in StrategyConcurrencyTest.
@JdbcTest(properties = {
        "spring.datasource.hikari.maximum-pool-size=" + HoldCapConcurrencyTest.POOL_SIZE,
        "spring.datasource.hikari.minimum-idle=" + HoldCapConcurrencyTest.POOL_SIZE})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(RedisAutoConfiguration.class)
@Import({StrategyTestConfig.class, InvariantChecker.class,
        RedisHoldStore.class, RedisStreamMetrics.class, RedisHoldWriter.class, RedisEventLoader.class,
        RedisStreamConsumer.class, RedisCatchUp.class, RedisFirstInventoryStrategy.class, RedisHoldStateReader.class})
@TestPropertySource(properties = {
        "stampede.inventory.redis.consumer.block=10ms",
        "stampede.inventory.redis.consumer.claim-min-idle=0s"})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class HoldCapConcurrencyTest {

    static final String STORES = "hold_cap_concurrency";
    static final int THREADS = 16;
    static final int POOL_SIZE = THREADS + 4;
    static final int LIMIT = 4;

    static final UUID USER = UUID.fromString("00000000-0000-4000-8000-0000000000b1");
    static final UUID VENUE = UUID.fromString("00000000-0000-4000-8000-0000000000b2");
    static final UUID SECTION = UUID.fromString("00000000-0000-4000-8000-0000000000b3");
    static final UUID EVENT = UUID.fromString("00000000-0000-4000-8000-0000000000b4");
    static final UUID TIER = UUID.fromString("00000000-0000-4000-8000-0000000000b5");
    /** Two seats per thread: thread t may use seats 2t and 2t+1, and nobody else's. */
    static final List<UUID> SEATS = seats(2 * THREADS);

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

    /**
     * Holds only. The counter only grows during the race, so a request rejected with LIMIT_EXCEEDED
     * must still not fit at the end: its seats plus the final count exceed the limit. A rejection
     * that would have fitted means the cap refused a request it should have allowed.
     */
    @ParameterizedTest(name = "parallelHoldsByOneUserNeverExceedTheLimit[{0}]")
    @MethodSource("strategies")
    void parallelHoldsByOneUserNeverExceedTheLimit(StrategyKind kind) {
        run(kind, new Scenario() {
            @Override
            public String name() {
                return "capOnlyHolds";
            }

            @Override
            public int perUserLimit(Fixture fixture) {
                return LIMIT;
            }

            @Override
            public RoundPlan plan(Random random, Fixture fixture, int threads) {
                List<List<Action>> plans = new ArrayList<>();
                for (int t = 0; t < threads; t++) {
                    plans.add(List.of(new Action.Hold(uuid(random), ownSeats(t, 1 + random.nextInt(2)))));
                }
                return new RoundPlan(List.of(), plans);
            }

            @Override
            public List<String> checkRace(List<CallResult> race) {
                List<String> violations = new ArrayList<>();
                int used = 0;
                for (CallResult result : race) {
                    if (result.outcome() instanceof Held held) {
                        used += held.seatIds().size();
                    }
                }
                if (used > LIMIT) {
                    violations.add("cap broken: " + used + " seats held, limit " + LIMIT);
                }
                for (CallResult result : race) {
                    if (result.outcome() instanceof Rejected rejected) {
                        if (rejected.reason() != HoldRejection.LIMIT_EXCEEDED) {
                            violations.add("unexpected rejection with disjoint seats: " + ConcurrencyHarness.describe(result));
                        } else if (rejected.seatIds().size() + used <= LIMIT) {
                            violations.add("false LIMIT_EXCEEDED: " + rejected.seatIds().size() + " seats would fit, "
                                    + used + " of " + LIMIT + " used at the end");
                        }
                    }
                }
                return violations;
            }
        });
    }

    /**
     * Holds, and half the threads release their hold again at once, so decrements race increments on
     * the same counter row. I8 checks after every round that the counter matches the live holds.
     */
    @ParameterizedTest(name = "parallelHoldsAndReleasesKeepTheCounterExact[{0}]")
    @MethodSource("strategies")
    void parallelHoldsAndReleasesKeepTheCounterExact(StrategyKind kind) {
        run(kind, new Scenario() {
            @Override
            public String name() {
                return "capHoldsAndReleases";
            }

            @Override
            public int perUserLimit(Fixture fixture) {
                return LIMIT;
            }

            @Override
            public RoundPlan plan(Random random, Fixture fixture, int threads) {
                List<List<Action>> plans = new ArrayList<>();
                for (int t = 0; t < threads; t++) {
                    UUID holdId = uuid(random);
                    List<Action> actions = new ArrayList<>();
                    actions.add(new Action.Hold(holdId, ownSeats(t, 1 + random.nextInt(2))));
                    if (random.nextBoolean()) {
                        actions.add(new Action.Release(holdId,
                                random.nextBoolean() ? ReleaseReason.RELEASED : ReleaseReason.SAGA_FAILED));
                    }
                    plans.add(actions);
                }
                return new RoundPlan(List.of(), plans);
            }
        });
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
        ConcurrencyHarness harness = new ConcurrencyHarness(strategy, jdbc, invariants, new Fixture(EVENT, USER, SEATS),
                Config.fromSystemProperties(THREADS), racers, roundHooks(kind));

        HarnessReport report = harness.run(scenario);

        report.writeTo(Path.of("build", "reports", "concurrency"));
        System.out.println(report.render());
        assertThat(report.failures()).as("%n%s", report.render()).isEmpty();
    }

    /** The event, with {@code kind} as its strategy, and two seats per thread. */
    private void insertFixture(StrategyKind kind) {
        jdbc.sql("TRUNCATE users, venues CASCADE").update();
        jdbc.sql("INSERT INTO users (id, email, password_hash) VALUES (?, 'capped@example.test', 'x')").param(USER).update();
        jdbc.sql("INSERT INTO venues (id, name, layout_json) VALUES (?, 'Cap venue', '{}')").param(VENUE).update();
        jdbc.sql("INSERT INTO sections (id, venue_id, code, default_tier) VALUES (?, ?, 'A', 'GOLD')")
                .params(SECTION, VENUE).update();
        jdbc.sql("""
                INSERT INTO events (id, venue_id, title, starts_at, drop_at, status, per_user_limit, strategy)
                VALUES (?, ?, 'Cap event', now() + interval '30 days', now() - interval '1 hour',
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

    private static List<UUID> ownSeats(int thread, int count) {
        return List.copyOf(SEATS.subList(2 * thread, 2 * thread + count));
    }

    /** Fixed ids; every second one starts with a hex digit >= 8, so Java and Postgres order differ (D-015). */
    private static List<UUID> seats(int count) {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String prefix = (i % 2 == 0 ? "1" : "9") + String.format("%07x", i);
            ids.add(UUID.fromString(prefix + "-0000-4000-8000-" + String.format("%012x", i)));
        }
        return List.copyOf(ids);
    }
}
