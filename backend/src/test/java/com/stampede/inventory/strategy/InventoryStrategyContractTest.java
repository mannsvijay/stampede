package com.stampede.inventory.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stampede.inventory.seat.SeatStatus;
import com.stampede.inventory.strategy.HoldOutcome.Held;
import com.stampede.inventory.strategy.HoldOutcome.Rejected;
import com.stampede.inventory.strategy.ReleaseOutcome.NotActive;
import com.stampede.inventory.strategy.ReleaseOutcome.NotFound;
import com.stampede.inventory.strategy.ReleaseOutcome.Released;
import com.stampede.inventory.strategy.ReleaseOutcome.NotExpired;
import com.stampede.testsupport.ExpectInvariantViolations;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import com.stampede.testsupport.TestDatabases;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The behaviour EVERY inventory strategy must have (M1.2, D-015; shared since M2.1; all three since
 * M2.2c). A contract test: one set of tests, run once per implementation by a subclass that supplies the
 * strategy and adds tests of its own mechanism. These tests set up state only through the strategy
 * itself and read the result from Postgres, so they mean the same for every strategy.
 * <p>
 * Subclasses: {@link PostgresLockingContractTest} (the Postgres strategies' row-lock, version and
 * direct-SQL tests, run by {@link PessimisticInventoryStrategyTest} and
 * {@link OptimisticInventoryStrategyTest}) and {@link RedisFirstInventoryStrategyTest}. All of them
 * share one Spring context: one database and one Redis key prefix.
 * <p>
 * Runs WITHOUT the usual test transaction ({@code NOT_SUPPORTED}), like SeedLoaderTest: the strategy's
 * own transaction must really commit or roll back, and the race tests need two real transactions on
 * two connections. Tables are emptied and the fixture inserted again before each test.
 */
// Redis beans for RedisFirstInventoryStrategyTest, imported here (not in a @TestConfiguration) so every
// subclass shares this context and HoldExpirySweeperTest's scan of this package finds no second bean.
// The consumer reads with a 10 ms block, so draining an empty stream doesn't wait a second.
@JdbcTest(properties = {
        "stampede.inventory.redis.consumer.block=10ms",
        "stampede.inventory.redis.consumer.claim-min-idle=0s"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(RedisAutoConfiguration.class)
@Import({StrategyTestConfig.class, RedisHoldStore.class, RedisStreamMetrics.class, RedisHoldWriter.class,
        RedisEventLoader.class, RedisStreamConsumer.class, RedisCatchUp.class, RedisFirstInventoryStrategy.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
abstract class InventoryStrategyContractTest {

    static final String REDIS_PREFIX = "strategy_contract";

    /**
     * One database and one Redis key prefix for every subclass, on the shared test servers
     * (TestDatabases). Every subclass inherits this method, so they share one Spring context.
     */
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabases.register(registry, "strategy_contract");
        TestDatabases.registerRedis(registry, REDIS_PREFIX);
    }

    /** Two threads for the race tests; a new pool per test, so no subclass inherits a shut-down one. */
    protected ExecutorService THREADS;

    static final UUID USER = UUID.fromString("00000000-0000-4000-8000-0000000000a1");
    static final UUID OTHER_USER = UUID.fromString("00000000-0000-4000-8000-0000000000a2");
    static final UUID VENUE = UUID.fromString("00000000-0000-4000-8000-000000000001");
    static final UUID SECTION = UUID.fromString("00000000-0000-4000-8000-000000000002");
    static final UUID EVENT_A = UUID.fromString("00000000-0000-4000-8000-00000000000a");
    static final UUID EVENT_B = UUID.fromString("00000000-0000-4000-8000-00000000000b");
    static final UUID TIER_A = UUID.fromString("00000000-0000-4000-8000-0000000000ca");
    static final UUID TIER_B = UUID.fromString("00000000-0000-4000-8000-0000000000cb");

    // Seat ids chosen so Java and Postgres sort them differently: UUID.compareTo compares the two
    // halves as SIGNED longs, so SEAT_2 (first hex digit 9, the sign bit set) sorts BEFORE SEAT_1 in
    // Java, while Postgres compares bytes unsigned and puts SEAT_1 first. See D-015.
    static final UUID SEAT_1 = UUID.fromString("10000000-0000-4000-8000-000000000001");
    static final UUID SEAT_2 = UUID.fromString("90000000-0000-4000-8000-000000000002");
    static final UUID SEAT_3 = UUID.fromString("30000000-0000-4000-8000-000000000003");
    static final UUID SEAT_4 = UUID.fromString("40000000-0000-4000-8000-000000000004");
    /** A seat of event B only. */
    static final UUID SEAT_B = UUID.fromString("50000000-0000-4000-8000-000000000005");

    static final Duration FIVE_MINUTES = Duration.ofMinutes(5);

    /** The strategy under test, from {@link #strategyUnderTest()}; set before each test. */
    InventoryStrategy strategy;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    DataSource dataSource;

    /** The strategy this subclass tests. */
    protected abstract InventoryStrategy strategyUnderTest();

    @BeforeEach
    void startThreadsAndPickStrategy() {
        THREADS = Executors.newFixedThreadPool(2);
        strategy = strategyUnderTest();
    }

    @AfterEach
    void stopThreads() {
        THREADS.shutdownNow();
    }

    @BeforeEach
    void insertFixture() {
        jdbc.sql("TRUNCATE users, venues CASCADE").update();
        jdbc.sql("INSERT INTO users (id, email, password_hash) VALUES (?, 'buyer@example.test', 'x')")
                .param(USER).update();
        jdbc.sql("INSERT INTO users (id, email, password_hash) VALUES (?, 'other@example.test', 'x')")
                .param(OTHER_USER).update();
        jdbc.sql("INSERT INTO venues (id, name, layout_json) VALUES (?, 'Test venue', '{}')").param(VENUE).update();
        jdbc.sql("INSERT INTO sections (id, venue_id, code, default_tier) VALUES (?, ?, 'A', 'GOLD')")
                .params(SECTION, VENUE).update();
        int number = 1;
        for (UUID seat : List.of(SEAT_1, SEAT_2, SEAT_3, SEAT_4, SEAT_B)) {
            jdbc.sql("INSERT INTO seats (id, section_id, row_label, number, x, y) VALUES (?, ?, 'A', ?, 0, 0)")
                    .params(seat, SECTION, number++).update();
        }
        // SEAT_2 is inserted first ON PURPOSE: a scan in physical order then meets SEAT_2 before SEAT_1,
        // the opposite of seat_id order. lockOrderIsPostgresSeatIdOrder relies on that to tell
        // "ORDER BY seat_id FOR UPDATE" apart from "whatever order the scan returns".
        insertEvent(EVENT_A, TIER_A, SEAT_2, SEAT_1, SEAT_3, SEAT_4);
        insertEvent(EVENT_B, TIER_B, SEAT_B);
    }

    protected void insertEvent(UUID event, UUID tier, UUID... seats) {
        jdbc.sql("""
                INSERT INTO events (id, venue_id, title, starts_at, drop_at, status, per_user_limit, strategy)
                VALUES (?, ?, 'Test event', now() + interval '30 days', now() - interval '1 hour',
                        'LIVE', 4, 'PESSIMISTIC')
                """).params(event, VENUE).update();
        jdbc.sql("INSERT INTO price_tiers (id, event_id, name, price_paise) VALUES (?, ?, 'GOLD', 150000)")
                .params(tier, event).update();
        for (UUID seat : seats) {
            jdbc.sql("INSERT INTO event_seats (event_id, seat_id, tier_id, status) VALUES (?, ?, ?, 'AVAILABLE')")
                    .params(event, seat, tier).update();
        }
    }

    // --- hold ------------------------------------------------------------------------------------

    @Test
    void holdTakesEverySeatAndCreatesAnActiveHold() {
        UUID holdId = UUID.randomUUID();

        HoldOutcome outcome = strategy.hold(request(holdId, EVENT_A, SEAT_3, SEAT_1, SEAT_2));

        assertThat(outcome).isInstanceOfSatisfying(Held.class, held -> {
            assertThat(held.holdId()).isEqualTo(holdId);
            assertThat(held.seatIds()).containsExactlyInAnyOrder(SEAT_1, SEAT_2, SEAT_3);
        });
        for (UUID seat : List.of(SEAT_1, SEAT_2, SEAT_3)) {
            assertThat(seat(EVENT_A, seat)).isEqualTo(new SeatRow(SeatStatus.HELD, holdId, null, 1));
        }
        assertThat(seat(EVENT_A, SEAT_4)).as("seat not in the request").isEqualTo(SeatRow.FRESH);

        HoldRow hold = hold(holdId).orElseThrow();
        assertThat(hold.status()).isEqualTo("ACTIVE");
        assertThat(hold.eventId()).isEqualTo(EVENT_A);
        assertThat(hold.userId()).isEqualTo(USER);
        assertThat(hold.seatIds()).containsExactlyInAnyOrder(SEAT_1, SEAT_2, SEAT_3);
        // Expiry comes from the database clock (D-015): created_at defaults to now(), and expires_at
        // must be now() + holdFor in the same transaction, so the gap is exactly the duration.
        assertThat(Duration.between(hold.createdAt(), hold.expiresAt())).isEqualTo(FIVE_MINUTES);
        assertThat(((Held) outcome).expiresAt()).isEqualTo(hold.expiresAt());
    }

    @Test
    void holdShorterThanOneSecondIsRejectedBeforeAnyStrategyRuns() {
        // Below 1 ms the expiry would equal created_at and break ck_holds_expires_after_created.
        assertThatThrownBy(() -> new HoldRequest(UUID.randomUUID(), EVENT_A, USER, List.of(SEAT_1),
                Duration.ofNanos(500_000)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void duplicateSeatIdIsRejectedBeforeAnyStrategyRuns() {
        // Validated in HoldRequest, so every strategy gets the check (PLAN note on M1.4).
        assertThatThrownBy(() -> request(UUID.randomUUID(), EVENT_A, SEAT_1, SEAT_2, SEAT_1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(SEAT_1.toString());
    }

    @Test
    void unknownSeatIsRejectedAndNothingChanges() {
        UUID unknown = UUID.randomUUID();
        UUID holdId = UUID.randomUUID();

        HoldOutcome outcome = strategy.hold(request(holdId, EVENT_A, SEAT_1, unknown));

        assertThat(outcome).isEqualTo(new Rejected(HoldRejection.UNKNOWN_SEAT, List.of(unknown)));
        assertThat(seat(EVENT_A, SEAT_1)).isEqualTo(SeatRow.FRESH);
        assertThat(hold(holdId)).isEmpty();
    }

    @Test
    void seatOfAnotherEventIsUnknown() {
        UUID holdId = UUID.randomUUID();

        HoldOutcome outcome = strategy.hold(request(holdId, EVENT_A, SEAT_1, SEAT_B));

        assertThat(outcome).isEqualTo(new Rejected(HoldRejection.UNKNOWN_SEAT, List.of(SEAT_B)));
        assertThat(seat(EVENT_A, SEAT_1)).isEqualTo(SeatRow.FRESH);
        assertThat(seat(EVENT_B, SEAT_B)).isEqualTo(SeatRow.FRESH);
        assertThat(hold(holdId)).isEmpty();
    }

    // --- takenAgoMs (D-016) ----------------------------------------------------------------------

    @Test
    void onlySeatTakenCarriesTakenAgoMs() {
        assertThatThrownBy(() -> new Rejected(HoldRejection.SEAT_TAKEN, List.of(SEAT_1), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Rejected(HoldRejection.LIMIT_EXCEEDED, List.of(SEAT_1), 5L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- per-user cap (D-005, M1.4) ------------------------------------------------------------

    @Test
    void holdAddsItsSeatsToTheUsersAllowance() {
        assertThat(used(EVENT_A)).as("no row before the first hold").isNull();

        strategy.hold(request(UUID.randomUUID(), EVENT_A, SEAT_1, SEAT_2));
        assertThat(used(EVENT_A)).isEqualTo(2);

        strategy.hold(request(UUID.randomUUID(), EVENT_A, SEAT_3));
        assertThat(used(EVENT_A)).isEqualTo(3);
        assertThat(usedBy(OTHER_USER, EVENT_A)).as("another user's allowance").isNull();
    }

    @Test
    void holdUpToExactlyTheLimitSucceeds() {
        // per_user_limit is 4 in the fixture
        assertThat(strategy.hold(request(UUID.randomUUID(), EVENT_A, SEAT_1, SEAT_2, SEAT_3, SEAT_4)))
                .isInstanceOf(Held.class);
        assertThat(used(EVENT_A)).isEqualTo(4);
    }

    @Test
    void theCapIsPerUserAndPerEvent() {
        strategy.hold(request(UUID.randomUUID(), EVENT_A, SEAT_1, SEAT_2, SEAT_3, SEAT_4));

        assertThat(strategy.hold(request(UUID.randomUUID(), EVENT_B, SEAT_B))).as("same user, other event")
                .isInstanceOf(Held.class);
        assertThat(used(EVENT_A)).isEqualTo(4);
        assertThat(used(EVENT_B)).isEqualTo(1);
    }

    // --- races (small; the 1000-run harness is M1.3) -----------------------------------------

    @Test
    void twoBuyersRaceForOneSeatAndExactlyOneWins() throws Exception {
        UUID holdA = UUID.randomUUID();
        UUID holdB = UUID.randomUUID();

        List<HoldOutcome> outcomes = race(
                () -> strategy.hold(request(holdA, EVENT_A, SEAT_1)),
                () -> strategy.hold(request(holdB, EVENT_A, SEAT_1)));

        List<Held> winners = outcomes.stream().filter(Held.class::isInstance).map(Held.class::cast).toList();
        assertThat(winners).hasSize(1);
        assertThat(outcomes).filteredOn(Rejected.class::isInstance).singleElement()
                .satisfies(rejected -> assertSeatTaken((HoldOutcome) rejected, SEAT_1));
        UUID winner = winners.get(0).holdId();
        assertThat(seat(EVENT_A, SEAT_1)).isEqualTo(new SeatRow(SeatStatus.HELD, winner, null, 1));
        assertThat(jdbc.sql("SELECT count(*) FROM holds").query(Long.class).single()).isEqualTo(1);
    }

    // --- helpers ---------------------------------------------------------------------------------

    /**
     * Runs {@code first} and then {@code second} against one hold, overlapping but in a fixed order.
     * Another session locks the hold row; {@code first} starts and blocks on it, then {@code second}
     * starts and queues behind it. Postgres serves a row's waiters in arrival order (the first waiter
     * holds the tuple lock the others queue on), so when the blocker lets go, {@code first} runs and
     * {@code second} only gets the row after {@code first} has committed. Every strategy's release and
     * sell lock the hold row first (Redis-first too, M2.2c), so this works for all three.
     */
    protected List<Object> inTurns(UUID holdId, Callable<?> first, Callable<?> second) throws Exception {
        try (Connection blocker = openTransaction()) {
            exec(blocker, "SELECT 1 FROM holds WHERE id = ? FOR UPDATE", holdId);
            Future<?> firstResult = THREADS.submit(first);
            waitUntilOneSessionIsBlockedOnALock();
            Future<?> secondResult = THREADS.submit(second);
            waitUntilSessionsAreBlockedOnALock(2);
            blocker.rollback();
            return List.of(firstResult.get(30, TimeUnit.SECONDS), secondResult.get(30, TimeUnit.SECONDS));
        }
    }

    /** A PENDING order of USER for this hold, as booking's step 1 commits it before the sale. */
    protected UUID pendingOrder(UUID holdId, UUID eventId) {
        UUID orderId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO orders (id, user_id, event_id, hold_id, status, total_paise, saga_state)
                VALUES (?, ?, ?, ?, 'PENDING', 150000, 'ORDER_CREATED')
                """).params(orderId, USER, eventId, holdId).update();
        return orderId;
    }

    static HoldRequest request(UUID holdId, UUID eventId, UUID... seats) {
        return requestBy(USER, holdId, eventId, seats);
    }

    static HoldRequest requestBy(UUID user, UUID holdId, UUID eventId, UUID... seats) {
        return new HoldRequest(holdId, eventId, user, List.of(seats), FIVE_MINUTES);
    }

    /** SEAT_TAKEN for exactly these seats, with a takenAgoMs (D-016) that is set and not negative. */
    protected static void assertSeatTaken(HoldOutcome outcome, UUID... seats) {
        assertThat(outcome).isInstanceOfSatisfying(Rejected.class, rejected -> {
            assertThat(rejected.reason()).isEqualTo(HoldRejection.SEAT_TAKEN);
            assertThat(rejected.seatIds()).containsExactly(seats);
            assertThat(rejected.takenAgoMs()).isNotNull().isNotNegative();
        });
    }

    /** USER's allowance in this event, or null when USER has no row yet. */
    protected Integer used(UUID eventId) {
        return usedBy(USER, eventId);
    }

    protected Integer usedBy(UUID user, UUID eventId) {
        return jdbc.sql("SELECT used FROM user_event_allowance WHERE event_id = ? AND user_id = ?")
                .params(eventId, user).query(Integer.class).optional().orElse(null);
    }

    /**
     * Starts both calls at the same moment and returns their results (rethrowing any exception). This
     * makes overlap likely, not certain; the deterministic lock tests above don't rely on timing.
     */
    @SafeVarargs
    protected final <T> List<T> race(Callable<T>... calls) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        for (Callable<T> call : calls) {
            futures.add(THREADS.submit(() -> {
                start.await();
                return call.call();
            }));
        }
        start.countDown();
        List<T> results = new ArrayList<>();
        for (Future<T> future : futures) {
            results.add(future.get(30, TimeUnit.SECONDS));
        }
        return results;
    }

    /** A plain JDBC connection with its own transaction, outside Spring: "another buyer". */
    protected Connection openTransaction() throws SQLException {
        Connection connection = dataSource.getConnection();
        connection.setAutoCommit(false);
        return connection;
    }

    protected static void exec(Connection connection, String sql, Object... params) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                statement.setObject(i + 1, params[i]);
            }
            statement.execute();
        }
    }

    /** Polls Postgres until exactly one session is waiting for a row lock (at most 10 s). */
    protected void waitUntilOneSessionIsBlockedOnALock() throws InterruptedException {
        waitUntilSessionsAreBlockedOnALock(1);
    }

    /**
     * Polls Postgres until exactly {@code sessions} sessions are waiting for a lock (at most 10 s).
     * Row locks and advisory locks both show as wait_event_type 'Lock'.
     */
    protected void waitUntilSessionsAreBlockedOnALock(int sessions) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            long waiting = jdbc.sql("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock'")
                    .query(Long.class).single();
            if (waiting == sessions) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("Expected exactly " + sessions + " session(s) waiting for a lock within 10 s");
    }

    record SeatRow(SeatStatus status, UUID holdId, UUID orderId, long version) {
        static final SeatRow FRESH = new SeatRow(SeatStatus.AVAILABLE, null, null, 0);
    }

    protected SeatRow seat(UUID eventId, UUID seatId) {
        return jdbc.sql("SELECT status, hold_id, order_id, version FROM event_seats WHERE event_id = ? AND seat_id = ?")
                .params(eventId, seatId)
                .query((rs, n) -> new SeatRow(SeatStatus.valueOf(rs.getString("status")),
                        rs.getObject("hold_id", UUID.class), rs.getObject("order_id", UUID.class),
                        rs.getLong("version")))
                .single();
    }

    record HoldRow(UUID eventId, UUID userId, List<UUID> seatIds, String status, Instant createdAt,
            Instant expiresAt) {
    }

    protected Optional<HoldRow> hold(UUID holdId) {
        return jdbc.sql("SELECT event_id, user_id, seat_ids, status, created_at, expires_at FROM holds WHERE id = ?")
                .param(holdId)
                .query((rs, n) -> new HoldRow(rs.getObject("event_id", UUID.class),
                        rs.getObject("user_id", UUID.class), uuids(rs.getArray("seat_ids")), rs.getString("status"),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("expires_at", OffsetDateTime.class).toInstant()))
                .optional();
    }

    protected static List<UUID> uuids(Array array) throws SQLException {
        return Arrays.asList((UUID[]) array.getArray());
    }
}
