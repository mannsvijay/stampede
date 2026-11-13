package com.stampede.inventory.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stampede.inventory.strategy.HoldOutcome.Held;
import com.stampede.inventory.strategy.HoldOutcome.Rejected;
import com.stampede.testsupport.TestRedis;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * M2.2b: loading an event from Postgres into Redis. What it writes, that it never touches a loaded
 * event, rebuilding after Redis lost its data, and loaders racing each other and hold attempts.
 */
class RedisEventLoaderTest extends RedisPersistenceTestSupport {

    @Test
    void loadsSeatsLimitSoldSeatsAndUnexpiredHoldsFromPostgres() {
        UUID orderId = sellDirectly(USER, SEATS.get(0));
        UUID active = holdInPostgresOnly(USER, FIVE_MINUTES, SEATS.get(1), SEATS.get(2));
        UUID expired = holdInPostgresOnly(OTHER_USER, Duration.ofSeconds(-10), SEATS.get(3));
        assertThat(store.isLoaded(EVENT)).isFalse();

        load();

        assertThat(store.isLoaded(EVENT)).isTrue();
        assertThat(redis.opsForHash().get(store.keys().meta(EVENT), "limit")).isEqualTo(Integer.toString(LIMIT));
        assertThat(redis.opsForSet().size(store.keys().seats(EVENT))).isEqualTo(SEATS.size());
        assertThat(store.seatValue(EVENT, SEATS.get(0))).startsWith("S:" + orderId + ":");
        // The active hold, exactly as Postgres has it (Redis' times are what Postgres stored).
        HoldRow row = holdRow(active).orElseThrow();
        RedisHoldStore.LiveHold live = store.liveHold(EVENT, active).orElseThrow();
        assertThat(live.userId()).isEqualTo(USER);
        assertThat(live.seatIds()).containsExactlyInAnyOrderElementsOf(row.seatIds());
        assertThat(live.expiresAt().toEpochMilli()).isEqualTo(row.expiresAt().toEpochMilli());
        Long ttl = redis.getExpire(store.keys().seat(EVENT, SEATS.get(1)), TimeUnit.MILLISECONDS);
        assertThat(ttl).isBetween(Duration.ofMinutes(4).toMillis(), FIVE_MINUTES.toMillis());
        // The expired hold is skipped: its seat is free in Redis (the sweeper closes it in Postgres).
        assertThat(store.liveHold(EVENT, expired)).isEmpty();
        assertThat(store.seatValue(EVENT, SEATS.get(3))).isNull();
        // The cap counts the bought seat and the active hold: 1 + 2.
        assertThat(store.used(EVENT, USER)).isEqualTo(3);
        assertThat(redis.getExpire(store.keys().user(EVENT, USER))).as("a sold member never expires").isEqualTo(-1);

        // And Redis now decides as Postgres would.
        assertThat(store.hold(request(OTHER_USER, SEATS.get(0)))).isInstanceOf(Rejected.class);
        assertThat(store.hold(request(OTHER_USER, SEATS.get(1)))).isInstanceOf(Rejected.class);
        assertThat(((Rejected) store.hold(request(USER, SEATS.get(4), SEATS.get(5)))).reason())
                .isEqualTo(HoldRejection.LIMIT_EXCEEDED);
        assertThat(store.hold(request(USER, SEATS.get(4)))).isInstanceOf(Held.class);
    }

    @Test
    void loadingALoadedEventWritesNothingAndNeverBringsAReleasedHoldBack() {
        load();
        UUID holdId = hold(USER, FIVE_MINUTES, SEATS.get(0));
        drain();
        releaseInRedis(holdId);   // its RELEASED entry is not applied yet: Postgres still says ACTIVE
        assertThat(holdStatus(holdId)).isEqualTo("ACTIVE");

        assertThat(loader.ensureLoaded(EVENT)).isFalse();
        assertThat(store.load(EVENT, new RedisHoldStore.EventSnapshot(LIMIT, SEATS, List.of(), List.of(),
                List.of(new RedisHoldStore.LiveHold(holdId, USER, List.of(SEATS.get(0)), java.time.Instant.now(),
                        java.time.Instant.now().plus(FIVE_MINUTES))))))
                .isInstanceOf(RedisHoldStore.Load.AlreadyLoaded.class);

        assertThat(store.liveHold(EVENT, holdId)).isEmpty();
        assertThat(store.seatValue(EVENT, SEATS.get(0))).isNull();
        drain();
        assertThat(holdStatus(holdId)).isEqualTo("RELEASED");
    }

    @Test
    void afterRedisLosesItsDataTheReconcilerReloadsTheEventFromPostgres() {
        load();
        UUID first = hold(USER, FIVE_MINUTES, SEATS.get(0), SEATS.get(1));
        UUID second = hold(OTHER_USER, FIVE_MINUTES, SEATS.get(2));
        drain();

        TestRedis.deleteKeys(redis, PREFIX);   // a Redis restart without its data: keys and stream gone

        assertThatThrownBy(() -> store.hold(request(USER, SEATS.get(5))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("not loaded");
        assertThat(reconciler.reconcileOnce().loaded()).isEqualTo(1);

        assertThat(store.liveHold(EVENT, first)).isPresent();
        assertThat(store.liveHold(EVENT, second)).isPresent();
        Rejected taken = (Rejected) store.hold(request(USERS.get(2), SEATS.get(1)));
        assertThat(taken.reason()).isEqualTo(HoldRejection.SEAT_TAKEN);
        assertThat(store.used(EVENT, USER)).isEqualTo(2);
        assertThat(((Rejected) store.hold(request(USER, SEATS.get(5), SEATS.get(6), SEATS.get(7)))).reason())
                .isEqualTo(HoldRejection.LIMIT_EXCEEDED);

        // The stream and group are recreated; new holds persist as before.
        UUID third = hold(USER, FIVE_MINUTES, SEATS.get(5));
        drain();
        assertThat(holdStatus(third)).isEqualTo("ACTIVE");
        assertThat(holdStatus(first)).isEqualTo("ACTIVE");
        assertThat(violations()).isEmpty();
    }

    @Test
    void loadersRacingEachOtherAndHoldAttemptsLoadOnceAndNeverDoubleHold() throws Exception {
        ExecutorService threads = Executors.newFixedThreadPool(16);
        try {
            for (int round = 0; round < 20; round++) {
                TestRedis.deleteKeys(redis, PREFIX);
                jdbc.sql("DELETE FROM user_event_allowance").update();
                jdbc.sql("UPDATE event_seats SET status = 'AVAILABLE', hold_id = NULL, order_id = NULL, version = 0")
                        .update();
                jdbc.sql("DELETE FROM holds").update();
                consumer.ensureGroup();
                UUID preHeld = holdInPostgresOnly(USER, FIVE_MINUTES, SEATS.get(0));

                CountDownLatch start = new CountDownLatch(1);
                AtomicInteger loads = new AtomicInteger();
                List<UUID> heldBy = Collections.synchronizedList(new ArrayList<>());
                List<Future<?>> done = new ArrayList<>();
                for (int t = 0; t < 8; t++) {
                    done.add(threads.submit(() -> {
                        start.await();
                        if (loader.ensureLoaded(EVENT)) {
                            loads.incrementAndGet();
                        }
                        return null;
                    }));
                }
                for (int t = 0; t < 8; t++) {
                    UUID buyer = USERS.get(1 + t % (USERS.size() - 1));
                    done.add(threads.submit(() -> {
                        start.await();
                        // Seat 0 is held in Postgres; seat 1 is free. Try both until the event is loaded.
                        for (int attempt = 0; attempt < 500; attempt++) {
                            try {
                                for (UUID seat : List.of(SEATS.get(0), SEATS.get(1))) {
                                    if (store.hold(request(buyer, seat)) instanceof Held held) {
                                        heldBy.add(seat);
                                    }
                                }
                                return null;
                            } catch (IllegalStateException notLoaded) {
                                Thread.onSpinWait();
                            }
                        }
                        throw new AssertionError("The event never became loaded");
                    }));
                }
                start.countDown();
                for (Future<?> future : done) {
                    future.get(30, TimeUnit.SECONDS);
                }

                assertThat(loads.get()).as("round %d: exactly one loader wrote", round).isEqualTo(1);
                assertThat(heldBy).as("round %d: seat 0 restored as held, seat 1 held once", round)
                        .containsExactly(SEATS.get(1));
                assertThat(store.liveHold(EVENT, preHeld)).as("round %d", round).isPresent();
                Set<UUID> heldSeats = new HashSet<>(heldBy);
                assertThat(heldSeats).hasSize(heldBy.size());
                drain();
                assertThat(violations()).as("round %d", round).isEmpty();
            }
        } finally {
            threads.shutdownNow();
        }
    }

    private static HoldRequest request(UUID user, UUID... seats) {
        return new HoldRequest(UUID.randomUUID(), EVENT, user, List.of(seats), FIVE_MINUTES);
    }
}
