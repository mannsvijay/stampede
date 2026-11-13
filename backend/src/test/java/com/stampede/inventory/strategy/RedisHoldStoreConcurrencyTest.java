package com.stampede.inventory.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.stampede.inventory.strategy.HoldOutcome.Held;
import com.stampede.inventory.strategy.HoldOutcome.Rejected;
import com.stampede.inventory.strategy.RedisHoldStore.LiveHold;
import com.stampede.testsupport.TestDatabases;
import com.stampede.testsupport.TestRedis;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.redis.DataRedisTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * M2.2a: races on the Redis hold store alone. 16 threads start together on one start gate; every
 * round uses a new event, so rounds don't affect each other. Rounds and seed come from the same
 * system properties as the M1.3 harness ({@code -Dstampede.harness.rounds}, {@code .seed}); round r
 * uses seed + r, and a failure names the round's seed so it can be replayed with rounds=1.
 * <p>
 * The full harness (Postgres state and the invariant checker after every round) runs against this
 * strategy from M2.2c.
 */
@DataRedisTest
@Import({RedisHoldStore.class, InventoryStrategyConfig.class})
class RedisHoldStoreConcurrencyTest {

    static final String PREFIX = "redis_hold_store_concurrency";
    static final int THREADS = 16;
    static final Duration FIVE_MINUTES = Duration.ofMinutes(5);

    @DynamicPropertySource
    static void redis(DynamicPropertyRegistry registry) {
        TestDatabases.registerRedis(registry, PREFIX);
    }

    @Autowired
    RedisHoldStore store;

    @Autowired
    StringRedisTemplate redis;

    private final int rounds = Integer.getInteger("stampede.harness.rounds", 100);
    private final long baseSeed = System.getProperty("stampede.harness.seed") != null
            ? Long.parseLong(System.getProperty("stampede.harness.seed"))
            : new SecureRandom().nextLong();
    private ExecutorService racers;

    /** One racing call: who asked for what, and what came back. */
    record Call(HoldRequest request, HoldOutcome outcome) {
    }

    @BeforeEach
    void startThreads() {
        TestRedis.deleteKeys(redis, PREFIX);
        racers = Executors.newFixedThreadPool(THREADS);
    }

    @AfterEach
    void stopThreadsAndDeleteKeys() {
        racers.shutdownNow();
        TestRedis.deleteKeys(redis, PREFIX);
    }

    /** 16 buyers, one seat: exactly one wins, and the other 15 are told that seat is taken. */
    @Test
    void sixteenBuyersRaceForOneSeatAndExactlyOneWins() throws Exception {
        for (int round = 0; round < rounds; round++) {
            Random random = new Random(baseSeed + round);
            String where = where("singleSeat", round);
            UUID event = uuid(random);
            UUID seat = uuid(random);
            store.loadEvent(event, List.of(seat), 4);

            List<HoldRequest> requests = new ArrayList<>();
            for (int t = 0; t < THREADS; t++) {
                requests.add(new HoldRequest(uuid(random), event, uuid(random), List.of(seat), FIVE_MINUTES));
            }
            List<Call> calls = race(requests);

            List<Call> winners = winners(calls);
            assertThat(winners).as("%s: winners", where).hasSize(1);
            for (Call call : calls) {
                if (call.outcome() instanceof Rejected rejected) {
                    assertThat(rejected.reason()).as(where).isEqualTo(HoldRejection.SEAT_TAKEN);
                    assertThat(rejected.seatIds()).as(where).containsExactly(seat);
                }
            }
            assertThat(store.holderOf(event, seat)).as(where).contains(winners.get(0).request().holdId());
        }
    }

    /**
     * 16 buyers ask for overlapping sets of 1 to 3 of 6 seats. No two winners share a seat, a loser
     * holds nothing (not even its free seats), and Redis' seat keys are exactly the winners' seats.
     */
    @Test
    void overlappingMultiSeatHoldsAreAllOrNothing() throws Exception {
        for (int round = 0; round < rounds; round++) {
            Random random = new Random(baseSeed + round);
            String where = where("overlappingSets", round);
            UUID event = uuid(random);
            List<UUID> seats = uuids(random, 6);
            store.loadEvent(event, seats, 4);

            List<HoldRequest> requests = new ArrayList<>();
            for (int t = 0; t < THREADS; t++) {
                List<UUID> shuffled = new ArrayList<>(seats);
                Collections.shuffle(shuffled, random);
                List<UUID> wanted = shuffled.subList(0, 1 + random.nextInt(3));
                requests.add(new HoldRequest(uuid(random), event, uuid(random), wanted, FIVE_MINUTES));
            }
            List<Call> calls = race(requests);

            List<Call> winners = winners(calls);
            assertThat(winners).as("%s: at least one request must win", where).isNotEmpty();
            Map<UUID, UUID> expectedHolder = new HashMap<>();  // seat -> winning hold
            for (Call winner : winners) {
                for (UUID seat : winner.request().seatIds()) {
                    UUID other = expectedHolder.put(seat, winner.request().holdId());
                    assertThat(other).as("%s: seat %s held by two winners", where, seat).isNull();
                }
                LiveHold live = store.liveHold(event, winner.request().holdId()).orElseThrow();
                assertThat(live.seatIds()).as(where).isEqualTo(winner.request().seatIds());
                assertThat(live.userId()).as(where).isEqualTo(winner.request().userId());
            }
            for (UUID seat : seats) {
                assertThat(store.holderOf(event, seat).orElse(null)).as("%s: holder of seat %s", where, seat)
                        .isEqualTo(expectedHolder.get(seat));
            }
            for (Call call : calls) {
                if (call.outcome() instanceof Rejected rejected) {
                    assertThat(rejected.reason()).as(where).isEqualTo(HoldRejection.SEAT_TAKEN);
                    assertThat(call.request().seatIds()).as(where).containsAll(rejected.seatIds());
                    assertThat(store.liveHold(event, call.request().holdId())).as("%s: a loser has no hold", where)
                            .isEmpty();
                    assertThat(store.used(event, call.request().userId())).as("%s: a loser used nothing", where)
                            .isZero();
                }
            }
        }
    }

    /**
     * One user, limit 3, 16 parallel requests for 1 or 2 different seats each (so no seat conflicts):
     * the user never ends up over the limit, and a request is only refused when it really didn't fit.
     */
    @Test
    void oneUserRacingPastTheCapNeverExceedsIt() throws Exception {
        int limit = 3;
        for (int round = 0; round < rounds; round++) {
            Random random = new Random(baseSeed + round);
            String where = where("capRace", round);
            UUID event = uuid(random);
            UUID user = uuid(random);
            List<UUID> seats = uuids(random, 2 * THREADS);
            store.loadEvent(event, seats, limit);

            List<HoldRequest> requests = new ArrayList<>();
            for (int t = 0; t < THREADS; t++) {
                List<UUID> wanted = random.nextBoolean()
                        ? List.of(seats.get(2 * t))
                        : List.of(seats.get(2 * t), seats.get(2 * t + 1));
                requests.add(new HoldRequest(uuid(random), event, user, wanted, FIVE_MINUTES));
            }
            List<Call> calls = race(requests);

            int won = winners(calls).stream().mapToInt(call -> call.request().seatIds().size()).sum();
            assertThat(won).as("%s: seats won", where).isLessThanOrEqualTo(limit);
            assertThat(store.used(event, user)).as("%s: used", where).isEqualTo(won);
            // limit - 1 can only be left over if every refused request asked for 2 seats.
            assertThat(won).as("%s: seats won", where).isGreaterThanOrEqualTo(limit - 1);
            Set<UUID> heldSeats = new HashSet<>();
            for (Call call : calls) {
                if (call.outcome() instanceof Rejected rejected) {
                    assertThat(rejected.reason()).as(where).isEqualTo(HoldRejection.LIMIT_EXCEEDED);
                    assertThat(won + call.request().seatIds().size()).as("%s: a refused request did not fit", where)
                            .isGreaterThan(limit);
                } else {
                    heldSeats.addAll(call.request().seatIds());
                }
            }
            for (UUID seat : seats) {
                assertThat(store.holderOf(event, seat).isPresent()).as("%s: seat %s held", where, seat)
                        .isEqualTo(heldSeats.contains(seat));
            }
        }
    }

    // ---- helpers ----

    /** Runs every request on its own thread, all released by one start gate. Any exception fails the test. */
    private List<Call> race(List<HoldRequest> requests) throws Exception {
        CountDownLatch ready = new CountDownLatch(requests.size());
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Call>> futures = new ArrayList<>();
        for (HoldRequest request : requests) {
            futures.add(racers.submit(() -> {
                ready.countDown();
                go.await();
                return new Call(request, store.hold(request));
            }));
        }
        assertThat(ready.await(30, TimeUnit.SECONDS)).as("threads ready").isTrue();
        go.countDown();
        List<Call> calls = new ArrayList<>();
        for (Future<Call> future : futures) {
            calls.add(future.get(30, TimeUnit.SECONDS));
        }
        return calls;
    }

    private static List<Call> winners(List<Call> calls) {
        return calls.stream().filter(call -> call.outcome() instanceof Held).toList();
    }

    private String where(String scenario, int round) {
        return scenario + " round " + round + " (replay: -Dstampede.harness.seed=" + (baseSeed + round)
                + " -Dstampede.harness.rounds=1)";
    }

    private static UUID uuid(Random random) {
        return new UUID(random.nextLong(), random.nextLong());
    }

    private static List<UUID> uuids(Random random, int count) {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ids.add(uuid(random));
        }
        return ids;
    }
}
