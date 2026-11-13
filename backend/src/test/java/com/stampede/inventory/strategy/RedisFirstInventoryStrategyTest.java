package com.stampede.inventory.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.stampede.inventory.seat.SeatStatus;
import com.stampede.inventory.strategy.HoldOutcome.Held;
import com.stampede.inventory.strategy.HoldOutcome.Rejected;
import com.stampede.inventory.strategy.ReleaseOutcome.NotActive;
import com.stampede.inventory.strategy.ReleaseOutcome.NotExpired;
import com.stampede.inventory.strategy.ReleaseOutcome.NotFound;
import com.stampede.inventory.strategy.ReleaseOutcome.Released;
import com.stampede.testsupport.ExpectInvariantViolations;
import com.stampede.testsupport.TestRedis;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * The Redis-first strategy (M2.2c, D-022) against the behaviour contract ({@link InventoryStrategyContractTest},
 * through {@link DrainingStrategy}), plus its own mechanism: loading on NOT_LOADED, the limit Redis took
 * from Postgres at load time, release and expiry with Redis' clock under the hold row lock, the three
 * steps of a sale (pin, Postgres, sold.lua) and what each failure leaves, and sell against release.
 * <p>
 * These tests replace, through the strategy's real paths, the Postgres-only setups of
 * {@link PostgresLockingContractTest} (holds and sales written with SQL, which Redis never sees).
 */
class RedisFirstInventoryStrategyTest extends InventoryStrategyContractTest {

    static final String CONSUMER = "contract";

    @Autowired
    RedisFirstInventoryStrategy redisFirst;

    @Autowired
    RedisHoldStore store;

    @Autowired
    RedisStreamConsumer consumer;

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    MeterRegistry meters;

    @Override
    protected InventoryStrategy strategyUnderTest() {
        return new DrainingStrategy(redisFirst, this::drain);
    }

    @BeforeEach
    void useRedisFirst() {
        resetRedis();
    }

    @AfterEach
    void deleteRedisKeys() {
        TestRedis.deleteKeys(redis, REDIS_PREFIX);
    }

    @Test
    void kindIsRedisFirst() {
        assertThat(strategy.kind()).isEqualTo(StrategyKind.REDIS_FIRST);
    }

    // --- hold ------------------------------------------------------------------------------------

    @Test
    void holdLoadsAnEventRedisDoesNotHaveYetAndRunsTheScriptOnceMore() {
        assertThat(store.isLoaded(EVENT_A)).isFalse();
        double attempts = attempts();

        assertThat(strategy.hold(request(UUID.randomUUID(), EVENT_A, SEAT_1))).isInstanceOf(Held.class);
        assertThat(store.isLoaded(EVENT_A)).isTrue();
        assertThat(attempts() - attempts).as("NOT_LOADED, load, then the script again").isEqualTo(2);

        strategy.hold(request(UUID.randomUUID(), EVENT_A, SEAT_2));
        assertThat(attempts() - attempts).as("loaded now: one script run").isEqualTo(3);
    }

    @Test
    void theLimitIsTheOnePostgresHadWhenRedisLoadedTheEvent() {
        // Replaces firstHoldLargerThanTheLimitIsRejectedWithoutCreatingAnAllowanceRow for this strategy:
        // the limit is set BEFORE the event is loaded, and this test checks that ordering explicitly.
        jdbc.sql("UPDATE events SET per_user_limit = 2 WHERE id = ?").param(EVENT_A).update();
        assertThat(store.isLoaded(EVENT_A)).as("not loaded until the first hold").isFalse();
        UUID holdId = UUID.randomUUID();

        HoldOutcome outcome = strategy.hold(request(holdId, EVENT_A, SEAT_1, SEAT_2, SEAT_3));

        assertThat(store.isLoaded(EVENT_A)).isTrue();
        assertThat(outcome).isEqualTo(new Rejected(HoldRejection.LIMIT_EXCEEDED, List.of(SEAT_1, SEAT_2, SEAT_3)));
        assertThat(used(EVENT_A)).as("no allowance row").isNull();
        assertThat(store.used(EVENT_A, USER)).isZero();
        assertThat(store.seatValue(EVENT_A, SEAT_1)).isNull();
        assertThat(seat(EVENT_A, SEAT_1)).isEqualTo(SeatRow.FRESH);
        assertThat(hold(holdId)).isEmpty();
        // Exactly the loaded limit, not more:
        assertThat(strategy.hold(request(UUID.randomUUID(), EVENT_A, SEAT_1, SEAT_2))).isInstanceOf(Held.class);
    }

    @Test
    void holdIsAllOrNothingWhenOneSeatIsTakenInRedis() {
        UUID otherHold = UUID.randomUUID();
        strategy.hold(requestBy(OTHER_USER, otherHold, EVENT_A, SEAT_2));
        UUID holdId = UUID.randomUUID();

        HoldOutcome outcome = strategy.hold(request(holdId, EVENT_A, SEAT_1, SEAT_2, SEAT_3));

        assertSeatTaken(outcome, SEAT_2);
        assertThat(store.seatValue(EVENT_A, SEAT_1)).isNull();
        assertThat(store.seatValue(EVENT_A, SEAT_3)).isNull();
        assertThat(seat(EVENT_A, SEAT_1)).isEqualTo(SeatRow.FRESH);
        assertThat(seat(EVENT_A, SEAT_2)).isEqualTo(new SeatRow(SeatStatus.HELD, otherHold, null, 1));
        assertThat(hold(holdId)).isEmpty();
    }

    @Test
    @ExpectInvariantViolations(rules = {"O1", "O5", "O7"},
            reason = "Strategy unit test of step 3 only (D-017): the order is a bare PENDING row and step 4 (booking marks it PAID) never runs, so its seats are SOLD to a PENDING order")
    void aSeatSoldThroughTheStrategyCanNotBeHeldAgain() {
        UUID holdId = UUID.randomUUID();
        strategy.hold(request(holdId, EVENT_A, SEAT_1));
        assertThat(strategy.sell(EVENT_A, holdId, pendingOrder(holdId, EVENT_A))).isInstanceOf(SellOutcome.Sold.class);

        assertSeatTaken(strategy.hold(requestBy(OTHER_USER, UUID.randomUUID(), EVENT_A, SEAT_1, SEAT_2)), SEAT_1);
        assertThat(seat(EVENT_A, SEAT_2)).isEqualTo(SeatRow.FRESH);
    }

    // --- release ---------------------------------------------------------------------------------

    @Test
    void releaseWritesBothStoresAtOnceWithoutWaitingForTheConsumer() {
        // The bare strategy: nothing drains the stream, so Postgres has never seen this hold.
        UUID holdId = UUID.randomUUID();
        assertThat(redisFirst.hold(request(holdId, EVENT_A, SEAT_1, SEAT_2))).isInstanceOf(Held.class);
        assertThat(hold(holdId)).as("consumer lag").isEmpty();

        ReleaseOutcome outcome = redisFirst.release(EVENT_A, holdId, ReleaseReason.RELEASED);

        // Catch-up persisted the hold, then the release closed it under its row lock.
        assertThat(outcome).isInstanceOfSatisfying(Released.class,
                released -> assertThat(released.seatIds()).containsExactlyInAnyOrder(SEAT_1, SEAT_2));
        assertThat(hold(holdId).orElseThrow().status()).isEqualTo("RELEASED");
        assertThat(seat(EVENT_A, SEAT_1)).isEqualTo(new SeatRow(SeatStatus.AVAILABLE, null, null, 2));
        assertThat(used(EVENT_A)).isZero();
        assertThat(store.seatValue(EVENT_A, SEAT_1)).isNull();
        assertThat(store.used(EVENT_A, USER)).isZero();

        // The consumer then finds both entries already applied.
        drain();
        assertThat(hold(holdId).orElseThrow().status()).isEqualTo("RELEASED");
        assertThat(seat(EVENT_A, SEAT_1)).isEqualTo(new SeatRow(SeatStatus.AVAILABLE, null, null, 2));
    }

    @Test
    void releaseOfAHoldRedisAlreadyExpiredIsNotActiveAndRecordsItAsExpired() {
        UUID holdId = UUID.randomUUID();
        strategy.hold(shortRequest(holdId, SEAT_3));
        waitForRedisExpiry(holdId);

        // e.g. the buyer cancels just after the TTL ran out, before the sweeper's tick
        assertThat(strategy.release(EVENT_A, holdId, ReleaseReason.RELEASED)).isEqualTo(new NotActive("EXPIRED"));

        assertThat(hold(holdId).orElseThrow().status()).isEqualTo("EXPIRED");
        assertThat(seat(EVENT_A, SEAT_3)).isEqualTo(new SeatRow(SeatStatus.AVAILABLE, null, null, 2));
        assertThat(used(EVENT_A)).isZero();
    }

    @Test
    void releaseOfAnUnknownHoldOrAHoldOfAnotherEventIsNotFound() {
        UUID holdId = UUID.randomUUID();
        strategy.hold(request(holdId, EVENT_A, SEAT_1));

        assertThat(strategy.release(EVENT_A, UUID.randomUUID(), ReleaseReason.RELEASED)).isEqualTo(new NotFound());
        assertThat(strategy.release(EVENT_B, holdId, ReleaseReason.RELEASED)).isEqualTo(new NotFound());
        assertThat(store.holderOf(EVENT_A, SEAT_1)).contains(holdId);
        assertThat(hold(holdId).orElseThrow().status()).isEqualTo("ACTIVE");
    }

    @Test
    void aFailedCheckoutsReleaseClosesTheHoldAsReleased() {
        UUID holdId = UUID.randomUUID();
        strategy.hold(request(holdId, EVENT_A, SEAT_4));

        assertThat(strategy.release(EVENT_A, holdId, ReleaseReason.SAGA_FAILED)).isEqualTo(new Released(List.of(SEAT_4)));
        assertThat(hold(holdId).orElseThrow().status()).isEqualTo("RELEASED");
        assertThat(seat(EVENT_A, SEAT_4)).isEqualTo(new SeatRow(SeatStatus.AVAILABLE, null, null, 2));
        assertThat(store.seatValue(EVENT_A, SEAT_4)).isNull();
        // and a second release changes nothing
        assertThat(strategy.release(EVENT_A, holdId, ReleaseReason.RELEASED)).isEqualTo(new NotActive("RELEASED"));
        assertThat(used(EVENT_A)).isZero();
    }

    @Test
    void expiryLeavesALiveHoldAloneAndClosesOneWhoseTimeRanOutInRedis() {
        UUID live = UUID.randomUUID();
        strategy.hold(request(live, EVENT_A, SEAT_1));
        UUID shortLived = UUID.randomUUID();
        strategy.hold(shortRequest(shortLived, SEAT_2));

        assertThat(strategy.release(EVENT_A, live, ReleaseReason.EXPIRED)).isInstanceOf(NotExpired.class);
        waitForRedisExpiry(shortLived);
        assertThat(strategy.release(EVENT_A, shortLived, ReleaseReason.EXPIRED)).isEqualTo(new Released(List.of(SEAT_2)));

        assertThat(hold(live).orElseThrow().status()).isEqualTo("ACTIVE");
        assertThat(hold(shortLived).orElseThrow().status()).isEqualTo("EXPIRED");
        assertThat(seat(EVENT_A, SEAT_2)).isEqualTo(new SeatRow(SeatStatus.AVAILABLE, null, null, 2));
        assertThat(used(EVENT_A)).isEqualTo(1);
    }

    // --- sell ------------------------------------------------------------------------------------

    @Test
    @ExpectInvariantViolations(rules = {"O1", "O5", "O7"},
            reason = "Strategy unit test of step 3 only (D-017): the order is a bare PENDING row and step 4 (booking marks it PAID) never runs, so its seats are SOLD to a PENDING order")
    void sellPinsThenSellsInPostgresThenMarksTheSeatsSoldInRedis() {
        UUID holdId = UUID.randomUUID();
        strategy.hold(request(holdId, EVENT_A, SEAT_1, SEAT_2));
        UUID orderId = pendingOrder(holdId, EVENT_A);

        SellOutcome outcome = strategy.sell(EVENT_A, holdId, orderId);

        assertThat(outcome).isInstanceOfSatisfying(SellOutcome.Sold.class,
                sold -> assertThat(sold.seatIds()).containsExactlyInAnyOrder(SEAT_1, SEAT_2));
        // Postgres: the record of the sale.
        assertThat(seat(EVENT_A, SEAT_1)).isEqualTo(new SeatRow(SeatStatus.SOLD, null, orderId, 2));
        assertThat(seat(EVENT_A, SEAT_2)).isEqualTo(new SeatRow(SeatStatus.SOLD, null, orderId, 2));
        assertThat(hold(holdId).orElseThrow().status()).isEqualTo("CONVERTED");
        assertThat(used(EVENT_A)).as("bought seats keep counting (D-016)").isEqualTo(2);
        // Redis: S: markers without expiry, the cap counts the seats forever, no hold hash left.
        for (UUID seat : List.of(SEAT_1, SEAT_2)) {
            assertThat(store.seatValue(EVENT_A, seat)).startsWith("S:" + orderId + ":");
            assertThat(redis.getExpire(store.keys().seat(EVENT_A, seat))).isEqualTo(-1L);
        }
        assertThat(store.liveHold(EVENT_A, holdId)).isEmpty();
        assertThat(store.hasSoldMember(EVENT_A, new RedisHoldStore.SoldMember(USER, holdId, 2))).isTrue();
        assertThat(store.used(EVENT_A, USER)).isEqualTo(2);
    }

    @Test
    @ExpectInvariantViolations(rules = {"O7"},
            reason = "The refused sale leaves the bare PENDING order this strategy unit test inserted; step 4 is booking's and never runs")
    void sellOfAHoldRedisAlreadyExpiredIsHoldExpiredAndChangesNothing() {
        UUID holdId = UUID.randomUUID();
        strategy.hold(shortRequest(holdId, SEAT_1));
        UUID orderId = pendingOrder(holdId, EVENT_A);
        waitForRedisExpiry(holdId);

        assertThat(strategy.sell(EVENT_A, holdId, orderId)).isInstanceOf(SellOutcome.HoldExpired.class);

        // Nothing written; the sweeper closes the hold (Redis' clock already freed the seat).
        assertThat(hold(holdId).orElseThrow().status()).isEqualTo("ACTIVE");
        assertThat(seat(EVENT_A, SEAT_1)).isEqualTo(new SeatRow(SeatStatus.HELD, holdId, null, 1));
        assertThat(store.seatValue(EVENT_A, SEAT_1)).isNull();
    }

    @Test
    @ExpectInvariantViolations(rules = {"O1", "O5", "O7"},
            reason = "Strategy unit test of step 3 only (D-017): the order is a bare PENDING row and step 4 (booking marks it PAID) never runs, so its seats are SOLD to a PENDING order")
    void sellOfAReleasedOrAlreadyConvertedHoldIsNotActive() {
        UUID released = UUID.randomUUID();
        strategy.hold(request(released, EVENT_A, SEAT_1));
        UUID releasedOrder = pendingOrder(released, EVENT_A);
        strategy.release(EVENT_A, released, ReleaseReason.RELEASED);

        assertThat(strategy.sell(EVENT_A, released, releasedOrder)).isEqualTo(new SellOutcome.NotActive("RELEASED"));
        assertThat(store.seatValue(EVENT_A, SEAT_1)).isNull();

        UUID sold = UUID.randomUUID();
        strategy.hold(request(sold, EVENT_A, SEAT_2));
        UUID soldOrder = pendingOrder(sold, EVENT_A);
        strategy.sell(EVENT_A, sold, soldOrder);

        assertThat(strategy.sell(EVENT_A, sold, soldOrder)).isEqualTo(new SellOutcome.NotActive("CONVERTED"));
        assertThat(seat(EVENT_A, SEAT_2)).isEqualTo(new SeatRow(SeatStatus.SOLD, null, soldOrder, 2));
    }

    @Test
    @ExpectInvariantViolations(rules = {"O1", "O5", "O7"},
            reason = "Strategy unit test of step 3 only (D-017): the order is a bare PENDING row and step 4 (booking marks it PAID) never runs, so its seats are SOLD to a PENDING order")
    void sellAfterRedisLostTheEventLoadsItAgainAndSells() {
        UUID holdId = UUID.randomUUID();
        strategy.hold(request(holdId, EVENT_A, SEAT_1));
        UUID orderId = pendingOrder(holdId, EVENT_A);
        TestRedis.deleteKeys(redis, REDIS_PREFIX);   // Redis restarted without its data

        assertThat(strategy.sell(EVENT_A, holdId, orderId)).isInstanceOf(SellOutcome.Sold.class);

        assertThat(seat(EVENT_A, SEAT_1)).isEqualTo(new SeatRow(SeatStatus.SOLD, null, orderId, 2));
        assertThat(store.seatValue(EVENT_A, SEAT_1)).startsWith("S:" + orderId + ":");
    }

    @Test
    @ExpectInvariantViolations(rules = {"O7"},
            reason = "The failed sale leaves the bare PENDING order this strategy unit test inserted; booking's compensation (which cancels it) is not part of this test")
    void aPostgresFailureAfterThePinLeavesAPinnedHoldThatTheFailedCheckoutsReleaseFrees() {
        UUID holdId = UUID.randomUUID();
        strategy.hold(request(holdId, EVENT_A, SEAT_1, SEAT_3));
        UUID orderId = pendingOrder(holdId, EVENT_A);
        // A trigger (test-only) makes the Postgres sale fail on SEAT_3, after the pin in Redis.
        jdbc.sql("""
                CREATE FUNCTION test_fail_redis_sale() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'test: simulated sale failure'; END $$
                """).update();
        jdbc.sql("CREATE TRIGGER test_fail_redis_sale BEFORE UPDATE ON event_seats FOR EACH ROW "
                + "WHEN (NEW.seat_id = '" + SEAT_3 + "') EXECUTE FUNCTION test_fail_redis_sale()").update();
        try {
            assertThatThrownBy(() -> strategy.sell(EVENT_A, holdId, orderId))
                    .hasStackTraceContaining("test: simulated sale failure");
        } finally {
            jdbc.sql("DROP TRIGGER test_fail_redis_sale ON event_seats").update();
            jdbc.sql("DROP FUNCTION test_fail_redis_sale()").update();
        }

        // Postgres rolled back; Redis still has the hold, pinned (no TTL), seats still taken.
        assertThat(hold(holdId).orElseThrow().status()).isEqualTo("ACTIVE");
        assertThat(seat(EVENT_A, SEAT_1)).isEqualTo(new SeatRow(SeatStatus.HELD, holdId, null, 1));
        assertThat(store.liveHold(EVENT_A, holdId)).isPresent();
        assertThat(redis.getExpire(store.keys().hold(EVENT_A, holdId))).isEqualTo(-1L);
        assertThat(store.holderOf(EVENT_A, SEAT_1)).contains(holdId);
        assertThat(store.seatValue(EVENT_A, SEAT_1)).as("no sale exists in Redis alone").startsWith("H:");

        // The checkout's compensation (D-017) releases the hold: both stores free the seats.
        assertThat(strategy.release(EVENT_A, holdId, ReleaseReason.SAGA_FAILED))
                .isInstanceOfSatisfying(Released.class,
                        released -> assertThat(released.seatIds()).containsExactlyInAnyOrder(SEAT_1, SEAT_3));
        assertThat(hold(holdId).orElseThrow().status()).isEqualTo("RELEASED");
        assertThat(seat(EVENT_A, SEAT_1)).isEqualTo(new SeatRow(SeatStatus.AVAILABLE, null, null, 2));
        assertThat(store.liveHold(EVENT_A, holdId)).isEmpty();
        assertThat(store.seatValue(EVENT_A, SEAT_1)).isNull();
        assertThat(store.used(EVENT_A, USER)).isZero();
        assertThat(used(EVENT_A)).isZero();
    }

    // Sell and cancel of the same hold take turns on the Postgres hold row, as for the Postgres
    // strategies: both lock it before running their Lua script.

    @Test
    @ExpectInvariantViolations(rules = {"O1", "O5", "O7"},
            reason = "Strategy unit test of step 3 only (D-017): the order is a bare PENDING row and step 4 (booking marks it PAID) never runs, so its seats are SOLD to a PENDING order")
    void sellThenCancelOfTheSameHoldTakeTurnsOnTheHoldRow() throws Exception {
        UUID holdId = UUID.randomUUID();
        strategy.hold(request(holdId, EVENT_A, SEAT_1, SEAT_2));
        UUID orderId = pendingOrder(holdId, EVENT_A);

        List<Object> outcomes = inTurns(holdId,
                () -> strategy.sell(EVENT_A, holdId, orderId),
                () -> strategy.release(EVENT_A, holdId, ReleaseReason.RELEASED));

        assertThat(outcomes.get(0)).isInstanceOf(SellOutcome.Sold.class);
        assertThat(outcomes.get(1)).as("cancel waited, then saw the sale").isEqualTo(new NotActive("CONVERTED"));
        assertThat(seat(EVENT_A, SEAT_1)).isEqualTo(new SeatRow(SeatStatus.SOLD, null, orderId, 2));
        assertThat(store.seatValue(EVENT_A, SEAT_1)).startsWith("S:" + orderId + ":");
        assertThat(store.seatValue(EVENT_A, SEAT_2)).startsWith("S:" + orderId + ":");
        assertThat(store.used(EVENT_A, USER)).isEqualTo(2);
    }

    @Test
    @ExpectInvariantViolations(rules = {"O7"},
            reason = "The refused sale leaves the bare PENDING order this strategy unit test inserted; step 4 is booking's and never runs")
    void cancelThenSellOfTheSameHoldTakeTurnsOnTheHoldRow() throws Exception {
        UUID holdId = UUID.randomUUID();
        strategy.hold(request(holdId, EVENT_A, SEAT_1, SEAT_2));
        UUID orderId = pendingOrder(holdId, EVENT_A);

        List<Object> outcomes = inTurns(holdId,
                () -> strategy.release(EVENT_A, holdId, ReleaseReason.RELEASED),
                () -> strategy.sell(EVENT_A, holdId, orderId));

        assertThat(outcomes.get(0)).isInstanceOf(Released.class);
        assertThat(outcomes.get(1)).as("sell waited, then saw the release")
                .isEqualTo(new SellOutcome.NotActive("RELEASED"));
        assertThat(seat(EVENT_A, SEAT_1)).isEqualTo(new SeatRow(SeatStatus.AVAILABLE, null, null, 2));
        assertThat(store.seatValue(EVENT_A, SEAT_1)).isNull();
        assertThat(store.seatValue(EVENT_A, SEAT_2)).isNull();
        assertThat(store.used(EVENT_A, USER)).isZero();
    }

    @Test
    void sellAndAFailedCheckoutsReleaseRaceAndExactlyOneWinsInBothStores() throws Exception {
        // Concurrency test of the sale protocol (CLAUDE.md): the two calls start together, 100 times.
        // Whichever locks the hold row first wins; the other must see its result, and Redis and Postgres
        // must agree. The fixture is rebuilt each round; the last line leaves nothing behind.
        int sold = 0;
        int released = 0;
        for (int round = 0; round < 100; round++) {
            insertFixture();
            resetRedis();
            UUID holdId = UUID.randomUUID();
            assertThat(strategy.hold(request(holdId, EVENT_A, SEAT_1, SEAT_2))).isInstanceOf(Held.class);
            UUID orderId = pendingOrder(holdId, EVENT_A);

            List<Object> outcomes = this.<Object>race(
                    () -> strategy.sell(EVENT_A, holdId, orderId),
                    () -> strategy.release(EVENT_A, holdId, ReleaseReason.SAGA_FAILED));

            if (outcomes.get(0) instanceof SellOutcome.Sold) {
                sold++;
                assertThat(outcomes.get(1)).as("round %d", round).isEqualTo(new NotActive("CONVERTED"));
                for (UUID seat : List.of(SEAT_1, SEAT_2)) {
                    assertThat(seat(EVENT_A, seat)).as("round %d", round)
                            .isEqualTo(new SeatRow(SeatStatus.SOLD, null, orderId, 2));
                    assertThat(store.seatValue(EVENT_A, seat)).as("round %d", round).startsWith("S:" + orderId + ":");
                }
                assertThat(store.used(EVENT_A, USER)).as("round %d", round).isEqualTo(2);
                assertThat(used(EVENT_A)).as("round %d", round).isEqualTo(2);
            } else {
                released++;
                assertThat(outcomes.get(0)).as("round %d", round).isEqualTo(new SellOutcome.NotActive("RELEASED"));
                assertThat(outcomes.get(1)).as("round %d", round).isInstanceOf(Released.class);
                for (UUID seat : List.of(SEAT_1, SEAT_2)) {
                    assertThat(seat(EVENT_A, seat)).as("round %d", round)
                            .isEqualTo(new SeatRow(SeatStatus.AVAILABLE, null, null, 2));
                    assertThat(store.seatValue(EVENT_A, seat)).as("round %d", round).isNull();
                }
                assertThat(store.used(EVENT_A, USER)).as("round %d", round).isZero();
                assertThat(used(EVENT_A)).as("round %d", round).isZero();
            }
            assertThat(store.liveHold(EVENT_A, holdId)).as("round %d", round).isEmpty();
        }
        System.out.printf("sell vs failed-checkout release: %d sold, %d released%n", sold, released);
        insertFixture();
        resetRedis();
    }

    // --- helpers ---------------------------------------------------------------------------------

    /** Applies everything in the stream to Postgres. Synchronized: one consumer name, one thread at a time. */
    synchronized void drain() {
        int handled;
        do {
            handled = consumer.pollOnce(CONSUMER) + consumer.reclaimOnce(CONSUMER);
        } while (handled > 0);
    }

    /** This context's Redis keys deleted, both events REDIS_FIRST, and the stream group ready. */
    private void resetRedis() {
        TestRedis.deleteKeys(redis, REDIS_PREFIX);
        jdbc.sql("UPDATE events SET strategy = 'REDIS_FIRST'").update();
        consumer.ensureGroup();
    }

    /** A hold of USER on EVENT_A that lasts one second (the shortest HoldRequest allows). */
    private static HoldRequest shortRequest(UUID holdId, UUID... seats) {
        return new HoldRequest(holdId, EVENT_A, USER, List.of(seats), Duration.ofSeconds(1));
    }

    /** Waits until Redis' clock has passed this hold's expiry, so its keys are gone. */
    private void waitForRedisExpiry(UUID holdId) {
        Instant expiresAt = store.liveHold(EVENT_A, holdId).orElseThrow().expiresAt();
        await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(50))
                .until(() -> store.nowMillis() > expiresAt.toEpochMilli() && store.liveHold(EVENT_A, holdId).isEmpty());
    }

    private double attempts() {
        Counter counter = meters.find(HoldMetrics.ATTEMPTS).tag("strategy", "REDIS_FIRST").counter();
        return counter == null ? 0 : counter.count();
    }
}
