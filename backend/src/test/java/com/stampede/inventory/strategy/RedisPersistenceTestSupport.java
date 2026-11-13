package com.stampede.inventory.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.stampede.control.invariants.InvariantChecker;
import com.stampede.control.invariants.Violation;
import com.stampede.inventory.seat.SeatStatus;
import com.stampede.inventory.strategy.HoldOutcome.Held;
import com.stampede.testsupport.TestDatabases;
import com.stampede.testsupport.TestRedis;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.sql.Array;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * M2.2b: the Redis-first persistence path against Redis and Postgres together. One Spring context
 * (a {@code @JdbcTest} slice plus Redis) shared by every subclass: its own database and its own key
 * prefix on the shared test servers.
 * <p>
 * Nothing runs in the background (the consumer thread and the reconciler schedule are off in tests):
 * each test drives {@link RedisStreamConsumer} and {@link RedisReconciler} itself, so it decides
 * exactly which entries are applied, in which order, and by which consumer name. Claim-min-idle is 0,
 * so {@link RedisStreamConsumer#reclaimOnce} takes over any pending entry at once.
 */
@JdbcTest(properties = {
        "stampede.inventory.redis.consumer.block=10ms",
        "stampede.inventory.redis.consumer.claim-min-idle=0s",
        "stampede.inventory.redis.reconciler.min-age=1s"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(RedisAutoConfiguration.class)
@Import({RedisPersistenceTestSupport.Beans.class, InvariantChecker.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
abstract class RedisPersistenceTestSupport {

    static final String PREFIX = "redis_persistence";

    @DynamicPropertySource
    static void stores(DynamicPropertyRegistry registry) {
        TestDatabases.register(registry, PREFIX);
        TestDatabases.registerRedis(registry, PREFIX);
    }

    /** What the Redis-first persistence path needs, and a meter registry the tests can read. */
    @TestConfiguration(proxyBeanMethods = false)
    @Import({SeatRows.class, HoldClosing.class, InventoryStrategyConfig.class, RedisHoldStore.class,
            RedisStreamMetrics.class, RedisHoldWriter.class, RedisEventLoader.class, RedisStreamConsumer.class,
            RedisReconciler.class})
    static class Beans {

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }

    static final Duration FIVE_MINUTES = Duration.ofMinutes(5);
    static final Duration ONE_SECOND = Duration.ofSeconds(1);
    static final int LIMIT = 4;

    static final UUID VENUE = id("venue");
    static final UUID SECTION = id("section");
    static final UUID EVENT = id("event");
    static final UUID TIER = id("tier");
    static final List<UUID> USERS = IntStream.range(0, 6).mapToObj(i -> id("user" + i)).toList();
    /** Name-based ids: their hex digits are spread, so Java and Postgres disagree on their order (D-015). */
    static final List<UUID> SEATS = IntStream.range(0, 24).mapToObj(i -> id("seat" + i)).toList();
    static final UUID USER = USERS.get(0);
    static final UUID OTHER_USER = USERS.get(1);

    @Autowired
    JdbcClient jdbc;

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    RedisHoldStore store;

    @Autowired
    RedisHoldWriter writer;

    @Autowired
    RedisEventLoader loader;

    @Autowired
    RedisStreamConsumer consumer;

    @Autowired
    RedisReconciler reconciler;

    @Autowired
    HoldClosing closing;

    @Autowired
    InvariantChecker invariants;

    @Autowired
    MeterRegistry meters;

    /** Counter values when the test started: the context (and its meters) is shared by every test. */
    private final java.util.Map<io.micrometer.core.instrument.Meter.Id, Double> counterBaseline = new java.util.HashMap<>();

    @BeforeEach
    void insertFixture() {
        counterBaseline.clear();
        meters.getMeters().stream()
                .filter(meter -> meter instanceof io.micrometer.core.instrument.Counter)
                .forEach(meter -> counterBaseline.put(meter.getId(),
                        ((io.micrometer.core.instrument.Counter) meter).count()));
        TestRedis.deleteKeys(redis, PREFIX);
        jdbc.sql("DROP TRIGGER IF EXISTS test_fail_hold_insert ON holds").update();
        jdbc.sql("TRUNCATE users, venues CASCADE").update();
        for (int i = 0; i < USERS.size(); i++) {
            jdbc.sql("INSERT INTO users (id, email, password_hash) VALUES (?, ?, 'x')")
                    .params(USERS.get(i), "redis" + i + "@example.test").update();
        }
        jdbc.sql("INSERT INTO venues (id, name, layout_json) VALUES (?, 'Redis venue', '{}')").param(VENUE).update();
        jdbc.sql("INSERT INTO sections (id, venue_id, code, default_tier) VALUES (?, ?, 'A', 'GOLD')")
                .params(SECTION, VENUE).update();
        jdbc.sql("""
                INSERT INTO events (id, venue_id, title, starts_at, drop_at, status, per_user_limit, strategy)
                VALUES (?, ?, 'Redis event', now() + interval '30 days', now() - interval '1 hour',
                        'LIVE', ?, 'REDIS_FIRST')
                """).params(EVENT, VENUE, LIMIT).update();
        jdbc.sql("INSERT INTO price_tiers (id, event_id, name, price_paise) VALUES (?, ?, 'GOLD', 150000)")
                .params(TIER, EVENT).update();
        int number = 1;
        for (UUID seat : SEATS) {
            jdbc.sql("INSERT INTO seats (id, section_id, row_label, number, x, y) VALUES (?, ?, 'A', ?, 0, 0)")
                    .params(seat, SECTION, number++).update();
            jdbc.sql("INSERT INTO event_seats (event_id, seat_id, tier_id, status) VALUES (?, ?, ?, 'AVAILABLE')")
                    .params(EVENT, seat, TIER).update();
        }
        consumer.ensureGroup();
    }

    @AfterEach
    void cleanUp() {
        jdbc.sql("DROP TRIGGER IF EXISTS test_fail_hold_insert ON holds").update();
        TestRedis.deleteKeys(redis, PREFIX);
    }

    // ---- driving Redis and the consumer ----

    /** Loads the event from Postgres and checks this call did it. */
    void load() {
        assertThat(loader.ensureLoaded(EVENT)).isTrue();
    }

    UUID hold(UUID user, Duration holdFor, UUID... seats) {
        UUID holdId = UUID.randomUUID();
        HoldOutcome outcome = store.hold(new HoldRequest(holdId, EVENT, user, List.of(seats), holdFor));
        assertThat(outcome).as("Redis hold of %s", Arrays.toString(seats)).isInstanceOf(Held.class);
        return holdId;
    }

    void releaseInRedis(UUID holdId) {
        assertThat(store.release(EVENT, holdId, ReleaseReason.RELEASED)).isInstanceOf(RedisHoldStore.Release.Released.class);
    }

    /** Applies everything in the stream as consumer {@code name}: new entries, then anything pending. */
    void drain(String name) {
        int handled;
        do {
            handled = consumer.pollOnce(name) + consumer.reclaimOnce(name);
        } while (handled > 0);
    }

    void drain() {
        drain("test");
    }

    /** Reads new entries as consumer {@code name} WITHOUT applying or acknowledging them (a crash). */
    List<MapRecord<String, Object, Object>> readWithoutAck(String name, int count) {
        return redis.opsForStream().read(Consumer.from(RedisKeys.GROUP, name), StreamReadOptions.empty().count(count),
                StreamOffset.create(store.keys().stream(), ReadOffset.lastConsumed()));
    }

    StreamEntry entry(MapRecord<String, Object, Object> record) {
        return StreamEntry.parse(record.getId().getValue(), record.getValue());
    }

    long pendingCount() {
        var summary = redis.opsForStream().pending(store.keys().stream(), RedisKeys.GROUP);
        return summary == null ? 0 : summary.getTotalPendingMessages();
    }

    long streamLength() {
        Long size = redis.opsForStream().size(store.keys().stream());
        return size == null ? 0 : size;
    }

    List<MapRecord<String, Object, Object>> deadLetters() {
        return redis.opsForStream().range(store.keys().deadLetters(), Range.unbounded());
    }

    void redisCommand(String... args) {
        redis.execute((RedisCallback<Object>) connection -> connection.execute(args[0],
                Arrays.stream(args).skip(1).map(arg -> arg.getBytes(StandardCharsets.UTF_8)).toArray(byte[][]::new)));
    }

    /** Waits until Redis' clock has passed this hold's expiry, so its keys are gone. */
    void waitForRedisExpiry(UUID holdId) {
        Instant expiresAt = store.liveHold(EVENT, holdId).orElseThrow().expiresAt();
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(50))
                .until(() -> store.nowMillis() > expiresAt.toEpochMilli() && store.liveHold(EVENT, holdId).isEmpty());
    }

    /** How much this counter grew during the current test. */
    double counter(String name, String... tags) {
        var counter = meters.find(name).tags(tags).counter();
        return counter == null ? 0 : counter.count() - counterBaseline.getOrDefault(counter.getId(), 0.0);
    }

    // ---- Postgres fixtures and reads ----

    /**
     * Makes the next {@code INSERT INTO holds} fail (a non-transient error) until
     * {@link #stopFailingHoldInserts()}. A real Postgres write failure, without mocking any code.
     */
    void failHoldInserts() {
        jdbc.sql("""
                CREATE OR REPLACE FUNCTION test_fail_hold_insert() RETURNS trigger AS $$
                BEGIN
                    RAISE EXCEPTION 'test: hold inserts are failing';
                END
                $$ LANGUAGE plpgsql
                """).update();
        jdbc.sql("DROP TRIGGER IF EXISTS test_fail_hold_insert ON holds").update();
        jdbc.sql("""
                CREATE TRIGGER test_fail_hold_insert BEFORE INSERT ON holds
                FOR EACH ROW EXECUTE FUNCTION test_fail_hold_insert()
                """).update();
    }

    void stopFailingHoldInserts() {
        jdbc.sql("DROP TRIGGER IF EXISTS test_fail_hold_insert ON holds").update();
    }

    /**
     * A SOLD seat as a finished checkout leaves it (the after-test invariant check sees it): the
     * CONVERTED hold, the PAID order, its item, payment and ticket, and the seat in the buyer's allowance.
     */
    UUID sellDirectly(UUID buyer, UUID seat) {
        UUID holdId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO holds (id, event_id, user_id, seat_ids, expires_at, status)
                VALUES (?, ?, ?, ?, now() + interval '5 minutes', 'CONVERTED')
                """).params(holdId, EVENT, buyer, new UUID[] {seat}).update();
        UUID orderId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO orders (id, user_id, event_id, hold_id, status, total_paise, saga_state)
                VALUES (?, ?, ?, ?, 'PAID', 150000, 'TICKETS_ISSUED')
                """).params(orderId, buyer, EVENT, holdId).update();
        jdbc.sql("""
                UPDATE event_seats SET status = 'SOLD', order_id = ?, version = version + 1
                WHERE event_id = ? AND seat_id = ?
                """).params(orderId, EVENT, seat).update();
        jdbc.sql("INSERT INTO order_items (order_id, event_id, seat_id, price_paise) VALUES (?, ?, ?, 150000)")
                .params(orderId, EVENT, seat).update();
        jdbc.sql("INSERT INTO payments (order_id, status, amount_paise) VALUES (?, 'SUCCEEDED', 150000)")
                .param(orderId).update();
        jdbc.sql("""
                INSERT INTO tickets (order_id, event_id, seat_id, holder_user_id, signature, status, key_id,
                                     section_code, row_label, seat_number)
                VALUES (?, ?, ?, ?, decode(repeat('00', 64), 'hex'), 'ISSUED', '0123abcd', 'A', 'A', 1)
                """).params(orderId, EVENT, seat, buyer).update();
        jdbc.sql("""
                INSERT INTO user_event_allowance (event_id, user_id, used) VALUES (?, ?, 1)
                ON CONFLICT (event_id, user_id) DO UPDATE SET used = user_event_allowance.used + 1
                """).params(EVENT, buyer).update();
        return orderId;
    }

    /** An ACTIVE hold written to Postgres only (as the consumer would), expiring {@code expiresIn} from now. */
    UUID holdInPostgresOnly(UUID user, Duration expiresIn, UUID... seats) {
        UUID holdId = UUID.randomUUID();
        Instant now = Instant.ofEpochMilli(store.nowMillis());
        Instant created = expiresIn.isNegative() ? now.plus(expiresIn).minusSeconds(300) : now;
        jdbc.sql("""
                INSERT INTO holds (id, event_id, user_id, seat_ids, created_at, expires_at, status)
                VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE')
                """).params(holdId, EVENT, user, seats, created.atOffset(java.time.ZoneOffset.UTC),
                now.plus(expiresIn).atOffset(java.time.ZoneOffset.UTC)).update();
        for (UUID seat : seats) {
            jdbc.sql("UPDATE event_seats SET status = 'HELD', hold_id = ?, version = version + 1 "
                    + "WHERE event_id = ? AND seat_id = ?").params(holdId, EVENT, seat).update();
        }
        jdbc.sql("""
                INSERT INTO user_event_allowance (event_id, user_id, used) VALUES (?, ?, ?)
                ON CONFLICT (event_id, user_id) DO UPDATE SET used = user_event_allowance.used + EXCLUDED.used
                """).params(EVENT, user, seats.length).update();
        return holdId;
    }

    record HoldRow(UUID userId, List<UUID> seatIds, String status, Instant createdAt, Instant expiresAt) {
    }

    Optional<HoldRow> holdRow(UUID holdId) {
        return jdbc.sql("SELECT user_id, seat_ids, status, created_at, expires_at FROM holds WHERE id = ?")
                .param(holdId)
                .query((rs, n) -> new HoldRow(rs.getObject("user_id", UUID.class), uuids(rs.getArray("seat_ids")),
                        rs.getString("status"), rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("expires_at", OffsetDateTime.class).toInstant()))
                .optional();
    }

    String holdStatus(UUID holdId) {
        return holdRow(holdId).map(HoldRow::status).orElse(null);
    }

    record SeatRow(SeatStatus status, UUID holdId, UUID orderId, long version) {
    }

    SeatRow seat(UUID seatId) {
        return jdbc.sql("SELECT status, hold_id, order_id, version FROM event_seats WHERE event_id = ? AND seat_id = ?")
                .params(EVENT, seatId)
                .query((rs, n) -> new SeatRow(SeatStatus.valueOf(rs.getString("status")),
                        rs.getObject("hold_id", UUID.class), rs.getObject("order_id", UUID.class), rs.getLong("version")))
                .single();
    }

    int allowance(UUID user) {
        return jdbc.sql("SELECT used FROM user_event_allowance WHERE event_id = ? AND user_id = ?")
                .params(EVENT, user)
                .query(Integer.class)
                .optional()
                .orElse(0);
    }

    /** Every hold row, seat row and allowance row, for comparing two states exactly. */
    List<String> postgresState() {
        List<String> rows = new ArrayList<>();
        rows.addAll(jdbc.sql("SELECT id || ' ' || status || ' ' || array_to_string(seat_ids, ',') || ' ' "
                + "|| created_at || ' ' || expires_at FROM holds ORDER BY id").query(String.class).list());
        rows.addAll(jdbc.sql("SELECT seat_id || ' ' || status || ' ' || coalesce(hold_id::text, '-') || ' ' "
                + "|| version FROM event_seats ORDER BY seat_id").query(String.class).list());
        rows.addAll(jdbc.sql("SELECT user_id || ' ' || used FROM user_event_allowance ORDER BY user_id")
                .query(String.class).list());
        return rows;
    }

    List<Violation> violations() {
        return invariants.check(EVENT, Duration.ZERO);
    }

    static UUID id(String name) {
        return UUID.nameUUIDFromBytes(("redis-persistence-" + name).getBytes(StandardCharsets.UTF_8));
    }

    private static List<UUID> uuids(Array array) throws SQLException {
        return Arrays.asList((UUID[]) array.getArray());
    }
}
