package com.stampede.inventory.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.stampede.inventory.strategy.HoldOutcome.Held;
import com.stampede.testsupport.TestRedis;
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
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

/**
 * M2.2b: the persistence path under concurrency. Per round, buyers hold and release seats in Redis
 * (some holds expire after 1 s) while two consumers apply the stream and keep stealing each other's
 * pending entries (claim-min-idle is 0, so entries are applied twice and out of order all the time),
 * and the reconciler runs alongside. After the buyers stop, everything is drained, and then:
 * <ul>
 *   <li>the Postgres invariant checker finds nothing (one holder per seat, holds own their seats, the
 *       allowance matches the active holds);</li>
 *   <li>Redis and Postgres agree on every 5-minute hold: live in Redis exactly when ACTIVE in
 *       Postgres (the 1 s holds may expire at any moment, so they are covered by the checker only);</li>
 *   <li>no hold Redis released is ACTIVE in Postgres.</li>
 * </ul>
 * Rounds: {@code -Dstampede.harness.rounds} (100 by default, as CI). A failing round prints its seed;
 * replay it with {@code -Dstampede.harness.seed=<seed> -Dstampede.harness.rounds=1}.
 */
class RedisPersistenceConcurrencyTest extends RedisPersistenceTestSupport {

    static final int ROUNDS = Integer.getInteger("stampede.harness.rounds", 100);
    static final Long SEED = Long.getLong("stampede.harness.seed");
    static final int BUYERS = 6;
    static final int OPERATIONS_PER_BUYER = 12;
    static final ExecutorService THREADS = Executors.newFixedThreadPool(BUYERS + 3);

    @AfterAll
    static void stopThreads() {
        THREADS.shutdownNow();
    }

    @Test
    void consumersReconcilerAndBuyersRacingLeaveRedisAndPostgresInAgreement() throws Exception {
        long baseSeed = SEED != null ? SEED : new Random().nextLong();
        for (int round = 0; round < ROUNDS; round++) {
            long seed = baseSeed + round;
            try {
                runRound(seed);
            } catch (AssertionError | Exception e) {
                throw new AssertionError("Round " + round + " failed; replay with -Dstampede.harness.seed=" + seed
                        + " -Dstampede.harness.rounds=1", e);
            }
        }
        // Evidence that the race reached the interesting paths, not only the happy one.
        System.out.printf("Redis persistence race, %d rounds: HELD applied=%.0f duplicate=%.0f stale=%.0f; "
                        + "RELEASED applied=%.0f duplicate=%.0f stale=%.0f; reconciler R1=%.0f R2=%.0f%n", ROUNDS,
                counter(RedisStreamMetrics.APPLIED, "type", "held", "result", "applied"),
                counter(RedisStreamMetrics.APPLIED, "type", "held", "result", "duplicate"),
                counter(RedisStreamMetrics.APPLIED, "type", "held", "result", "stale"),
                counter(RedisStreamMetrics.APPLIED, "type", "released", "result", "applied"),
                counter(RedisStreamMetrics.APPLIED, "type", "released", "result", "duplicate"),
                counter(RedisStreamMetrics.APPLIED, "type", "released", "result", "stale"),
                counter(RedisStreamMetrics.REPAIRS, "rule", "R1_roll_forward"),
                counter(RedisStreamMetrics.REPAIRS, "rule", "R2_close"));
    }

    private void runRound(long seed) throws Exception {
        reset();
        Map<UUID, Duration> holdLengths = new java.util.concurrent.ConcurrentHashMap<>();
        Set<UUID> releasedInRedis = Collections.synchronizedSet(new HashSet<>());
        AtomicBoolean buying = new AtomicBoolean(true);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> buyers = new ArrayList<>();
        for (int b = 0; b < BUYERS; b++) {
            UUID buyer = USERS.get(b);
            Random random = new Random(seed * 31 + b);
            buyers.add(THREADS.submit(() -> {
                start.await();
                List<UUID> mine = new ArrayList<>();
                for (int op = 0; op < OPERATIONS_PER_BUYER; op++) {
                    if (!mine.isEmpty() && random.nextInt(3) == 0) {
                        UUID holdId = mine.remove(random.nextInt(mine.size()));
                        if (store.release(EVENT, holdId, ReleaseReason.RELEASED) instanceof RedisHoldStore.Release.Released) {
                            releasedInRedis.add(holdId);
                        }
                        continue;
                    }
                    List<UUID> seats = new ArrayList<>(new HashSet<>(List.of(
                            SEATS.get(random.nextInt(SEATS.size())), SEATS.get(random.nextInt(SEATS.size())))));
                    Duration length = random.nextInt(4) == 0 ? ONE_SECOND : FIVE_MINUTES;
                    UUID holdId = UUID.randomUUID();
                    if (store.hold(new HoldRequest(holdId, EVENT, buyer, seats, length)) instanceof Held) {
                        holdLengths.put(holdId, length);
                        mine.add(holdId);
                    }
                }
                return null;
            }));
        }
        List<Future<?>> background = new ArrayList<>();
        for (String name : List.of("a", "b")) {
            background.add(THREADS.submit(() -> {
                start.await();
                while (buying.get()) {
                    consumer.pollOnce(name);
                    consumer.reclaimOnce(name);
                }
                return null;
            }));
        }
        background.add(THREADS.submit(() -> {
            start.await();
            while (buying.get()) {
                reconciler.reconcile(EVENT);
            }
            return null;
        }));

        start.countDown();
        for (Future<?> buyer : buyers) {
            buyer.get(60, TimeUnit.SECONDS);
        }
        buying.set(false);
        for (Future<?> task : background) {
            task.get(60, TimeUnit.SECONDS);
        }
        drain();

        assertThat(violations()).isEmpty();
        assertThat(pendingCount()).isZero();
        // Redis and Postgres never really disagree here, so "Postgres wins" must never fire: a wrong
        // conflict would release a good hold on both sides and pass the agreement check below.
        assertThat(counter(RedisStreamMetrics.APPLIED, "type", "held", "result", "conflict")).isZero();
        assertThat(counter(RedisStreamMetrics.REPAIRS, "rule", "R4_release_in_redis")).isZero();
        Map<UUID, String> statuses = new HashMap<>();
        jdbc.sql("SELECT id, status FROM holds").query((rs, n) -> statuses.put(rs.getObject("id", UUID.class),
                rs.getString("status"))).list();
        Set<UUID> liveInRedis = new HashSet<>(store.liveHoldIds(EVENT));
        for (Map.Entry<UUID, Duration> hold : holdLengths.entrySet()) {
            UUID holdId = hold.getKey();
            assertThat(statuses).as("every hold Redis granted has a row").containsKey(holdId);
            if (releasedInRedis.contains(holdId)) {
                assertThat(statuses.get(holdId)).as("released hold %s", holdId).isNotEqualTo("ACTIVE");
            } else if (hold.getValue().equals(FIVE_MINUTES)) {
                assertThat(statuses.get(holdId).equals("ACTIVE")).as("hold %s", holdId)
                        .isEqualTo(liveInRedis.contains(holdId));
            }
        }
    }

    private void reset() {
        TestRedis.deleteKeys(redis, PREFIX);
        jdbc.sql("DELETE FROM user_event_allowance").update();
        jdbc.sql("UPDATE event_seats SET status = 'AVAILABLE', hold_id = NULL, order_id = NULL, version = 0").update();
        jdbc.sql("DELETE FROM holds").update();
        consumer.ensureGroup();
        load();
    }
}
