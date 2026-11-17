package com.stampede.inventory.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.stampede.inventory.strategy.HoldOutcome.Held;
import com.stampede.testsupport.TestRedis;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Review follow-up M2 (D-022): sales racing the stream consumers, the reconciler and the expiry sweeper,
 * through the strategy's real paths. Per round, buyers hold, sell (each sale with its own order, completed
 * or cancelled as checkout would) and release; a sweeper thread expires 1 s holds the way
 * {@code HoldExpirySweeper} does (one {@code release(…, EXPIRED)} per expired ACTIVE hold); two consumers
 * and the reconciler loop alongside. Every sale waits a few random milliseconds between its Postgres
 * commit and sold.lua (a slow network), which is the window reconciler rules R3 and R4 must handle.
 * <p>
 * After the buyers stop and the stream is drained, strictly:
 * <ul>
 *   <li>the invariant checker (I10 included) finds nothing, and nothing is pending;</li>
 *   <li>"Postgres wins" never fired, and the reconciler never released a hold in Redis (R3 or R4):
 *       nothing here really disagrees, so a release would be a sold or good hold freed by mistake;</li>
 *   <li>every sale is CONVERTED in Postgres, its seats carry their {@code S:} marker in Redis, and its
 *       buyer's cap counts it with a {@code +inf} member.</li>
 * </ul>
 * And while the buyers run (M2.2d), a checker thread runs invariant 5 the way the monitor does (grace 30 s,
 * the first Redis read taken in pages while every write above lands between them): I10 must never fire.
 * Consumer lag over the 2 s window would make it fire; that is a real runtime signal, not to be loosened.
 * Rounds and replay as {@link RedisPersistenceConcurrencyTest}.
 */
class RedisSaleRaceConcurrencyTest extends RedisPersistenceTestSupport {

    static final int ROUNDS = Integer.getInteger("stampede.harness.rounds", 100);
    static final Long SEED = Long.getLong("stampede.harness.seed");
    static final int BUYERS = 6;
    static final int OPERATIONS_PER_BUYER = 16;
    static final long PRICE = 150000;
    /** The monitor's grace at runtime (application.yml, invariants monitor {@code grace: PT30S}). */
    static final Duration MONITOR_GRACE = Duration.ofSeconds(30);
    static final ExecutorService THREADS = Executors.newFixedThreadPool(BUYERS + 5);

    @Autowired
    RedisFirstInventoryStrategy strategy;

    @Autowired
    RedisCatchUp catchUp;

    private final AtomicInteger sold = new AtomicInteger();
    private final AtomicInteger refused = new AtomicInteger();
    private final AtomicInteger released = new AtomicInteger();
    private final AtomicInteger expired = new AtomicInteger();
    private final AtomicInteger checksDuringRace = new AtomicInteger();

    @AfterAll
    static void stopThreads() {
        THREADS.shutdownNow();
    }

    @Test
    void salesRacingConsumersReconcilerAndSweeperLeaveBothStoresInAgreement() throws Exception {
        long baseSeed = SEED != null ? SEED : new Random().nextLong();
        strategy.afterPostgresSale(() -> pause(ThreadLocalRandom.current().nextInt(0, 21)));
        try {
            for (int round = 0; round < ROUNDS; round++) {
                long seed = baseSeed + round;
                try {
                    runRound(seed);
                } catch (AssertionError | Exception e) {
                    throw new AssertionError("Round " + round + " failed; replay with -Dstampede.harness.seed=" + seed
                            + " -Dstampede.harness.rounds=1", e);
                }
            }
        } finally {
            strategy.afterPostgresSale(() -> { });
        }
        // Evidence that the race reached the interesting paths.
        System.out.printf("Redis sale race, %d rounds: sold=%d refused=%d released=%d expired=%d; I10 checks during "
                        + "the race=%d; reconciler R3 finish=%.0f R3 marker=%.0f R4 finish=%.0f R1=%.0f R2=%.0f%n", ROUNDS,
                sold.get(), refused.get(), released.get(), expired.get(), checksDuringRace.get(),
                counter(RedisStreamMetrics.REPAIRS, "rule", "R3_finish_sale"),
                counter(RedisStreamMetrics.REPAIRS, "rule", "R3_sold_marker"),
                counter(RedisStreamMetrics.REPAIRS, "rule", "R4_finish_sale"),
                counter(RedisStreamMetrics.REPAIRS, "rule", "R1_roll_forward"),
                counter(RedisStreamMetrics.REPAIRS, "rule", "R2_close"));
    }

    private void runRound(long seed) throws Exception {
        reset();
        Map<UUID, UUID> orderOfSoldHold = new ConcurrentHashMap<>();
        Map<UUID, List<UUID>> seatsOfSoldHold = new ConcurrentHashMap<>();
        Queue<Throwable> backgroundFailures = new ConcurrentLinkedQueue<>();
        AtomicBoolean buying = new AtomicBoolean(true);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> buyers = new ArrayList<>();
        for (int b = 0; b < BUYERS; b++) {
            UUID buyer = USERS.get(b);
            Random random = new Random(seed * 31 + b);
            buyers.add(THREADS.submit(() -> {
                start.await();
                List<UUID> mine = new ArrayList<>();
                // 1 s holds, kept for the end: each is then sold close to its expiry (below).
                Map<UUID, Long> shortHolds = new java.util.LinkedHashMap<>();
                for (int op = 0; op < OPERATIONS_PER_BUYER; op++) {
                    pause(random.nextInt(41));   // think time
                    int action = random.nextInt(3);
                    if (!mine.isEmpty() && action == 0) {
                        UUID holdId = mine.remove(random.nextInt(mine.size()));
                        if (strategy.release(EVENT, holdId, ReleaseReason.RELEASED) instanceof ReleaseOutcome.Released) {
                            released.incrementAndGet();
                        }
                        continue;
                    }
                    if (!mine.isEmpty() && action == 1) {
                        UUID holdId = mine.remove(random.nextInt(mine.size()));
                        sell(buyer, holdId, orderOfSoldHold, seatsOfSoldHold);
                        continue;
                    }
                    List<UUID> seats = new ArrayList<>(new HashSet<>(List.of(
                            SEATS.get(random.nextInt(SEATS.size())), SEATS.get(random.nextInt(SEATS.size())))));
                    Duration length = random.nextInt(4) == 0 ? ONE_SECOND : FIVE_MINUTES;
                    UUID holdId = UUID.randomUUID();
                    long heldAt = System.nanoTime();
                    if (strategy.hold(new HoldRequest(holdId, EVENT, buyer, seats, length)) instanceof Held) {
                        if (length.equals(ONE_SECOND)) {
                            shortHolds.put(holdId, heldAt);
                        } else {
                            mine.add(holdId);
                        }
                    }
                }
                // Sell each 1 s hold within 100 ms either side of its expiry: pin.lua against the Redis TTL,
                // the sweeper and the reconciler, with the sale landing on either side.
                for (Map.Entry<UUID, Long> hold : shortHolds.entrySet()) {
                    long sellAt = hold.getValue() + TimeUnit.MILLISECONDS.toNanos(900 + random.nextInt(201));
                    pause((int) Math.max(0, TimeUnit.NANOSECONDS.toMillis(sellAt - System.nanoTime())));
                    sell(buyer, hold.getKey(), orderOfSoldHold, seatsOfSoldHold);
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
        background.add(THREADS.submit(() -> {
            start.await();
            while (buying.get()) {
                sweepOnce(backgroundFailures);
                pause(5);
            }
            return null;
        }));
        Queue<String> i10DuringRace = new ConcurrentLinkedQueue<>();
        background.add(THREADS.submit(() -> {
            start.await();
            while (buying.get()) {
                invariants.check(EVENT, MONITOR_GRACE).stream()
                        .filter(v -> v.ruleId().equals("I10"))
                        .forEach(v -> i10DuringRace.add(v.detail()));
                checksDuringRace.incrementAndGet();
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

        assertThat(backgroundFailures).as("the sweeper never failed to expire a hold").isEmpty();
        assertThat(i10DuringRace).as("I10 at runtime grace, checked while the buyers ran").isEmpty();
        assertThat(violations()).isEmpty();
        assertThat(pendingCount()).isZero();
        assertThat(counter(RedisStreamMetrics.APPLIED, "type", "held", "result", "conflict")).isZero();
        assertThat(counter(RedisStreamMetrics.REPAIRS, "rule", "R4_release_in_redis")).isZero();
        assertThat(counter(RedisStreamMetrics.REPAIRS, "rule", "R3_release_in_redis"))
                .as("R3 released a hold in Redis: a sale in flight freed instead of finished?").isZero();
        for (Map.Entry<UUID, UUID> sale : orderOfSoldHold.entrySet()) {
            UUID holdId = sale.getKey();
            UUID orderId = sale.getValue();
            List<UUID> seats = seatsOfSoldHold.get(holdId);
            assertThat(holdStatus(holdId)).as("sold hold %s", holdId).isEqualTo("CONVERTED");
            for (UUID seat : seats) {
                assertThat(store.seatValue(EVENT, seat)).as("seat %s of sold hold %s", seat, holdId)
                        .startsWith("S:" + orderId + ":");
            }
            UUID buyer = holdRow(holdId).orElseThrow().userId();
            assertThat(store.hasSoldMember(EVENT, new RedisHoldStore.SoldMember(buyer, holdId, seats.size())))
                    .as("cap member of sold hold %s", holdId).isTrue();
        }
    }

    /** Checkout's inventory steps for one hold: persist it, create its order, sell, then finish the order. */
    private void sell(UUID buyer, UUID holdId, Map<UUID, UUID> orderOfSoldHold, Map<UUID, List<UUID>> seatsOfSoldHold) {
        catchUp.catchUp(EVENT, holdId);
        if (holdRow(holdId).isEmpty()) {
            refused.incrementAndGet();   // Redis no longer has it and Postgres never did: nothing to order
            return;
        }
        UUID orderId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO orders (id, user_id, event_id, hold_id, status, total_paise, saga_state)
                VALUES (?, ?, ?, ?, 'PENDING', 0, 'ORDER_CREATED')
                """).params(orderId, buyer, EVENT, holdId).update();
        if (strategy.sell(EVENT, holdId, orderId) instanceof SellOutcome.Sold soldOutcome) {
            completeOrder(buyer, orderId, soldOutcome.seatIds());
            seatsOfSoldHold.put(holdId, soldOutcome.seatIds());
            orderOfSoldHold.put(holdId, orderId);
            sold.incrementAndGet();
        } else {
            jdbc.sql("UPDATE orders SET status = 'CANCELLED', saga_state = 'ORDER_CANCELLED' WHERE id = ?")
                    .param(orderId).update();
            refused.incrementAndGet();
        }
    }

    /** A finished checkout, as {@link #sellDirectly} leaves it: items, payment, tickets, PAID. */
    private void completeOrder(UUID buyer, UUID orderId, List<UUID> seats) {
        for (UUID seat : seats) {
            jdbc.sql("INSERT INTO order_items (order_id, event_id, seat_id, price_paise) VALUES (?, ?, ?, ?)")
                    .params(orderId, EVENT, seat, PRICE).update();
            jdbc.sql("""
                    INSERT INTO tickets (order_id, event_id, seat_id, holder_user_id, signature, status, key_id,
                                         section_code, row_label, seat_number)
                    VALUES (?, ?, ?, ?, decode(repeat('00', 64), 'hex'), 'ISSUED', '0123abcd', 'A', 'A', ?)
                    """).params(orderId, EVENT, seat, buyer, SEATS.indexOf(seat) + 1).update();
        }
        long total = PRICE * seats.size();
        jdbc.sql("INSERT INTO payments (order_id, status, amount_paise) VALUES (?, 'SUCCEEDED', ?)")
                .params(orderId, total).update();
        jdbc.sql("UPDATE orders SET status = 'PAID', saga_state = 'TICKETS_ISSUED', total_paise = ? WHERE id = ?")
                .params(total, orderId).update();
    }

    /** One sweep as HoldExpirySweeper does it: release(EXPIRED) for every ACTIVE hold past its expiry. */
    private void sweepOnce(Queue<Throwable> failures) {
        List<UUID> due = jdbc.sql("SELECT id FROM holds WHERE event_id = ? AND status = 'ACTIVE' AND expires_at <= now()")
                .param(EVENT)
                .query(UUID.class)
                .list();
        for (UUID holdId : due) {
            try {
                if (strategy.release(EVENT, holdId, ReleaseReason.EXPIRED) instanceof ReleaseOutcome.Released) {
                    expired.incrementAndGet();
                }
            } catch (RuntimeException e) {
                failures.add(e);
            }
        }
    }

    private static void pause(int millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void reset() {
        TestRedis.deleteKeys(redis, PREFIX);
        jdbc.sql("DELETE FROM tickets").update();
        jdbc.sql("DELETE FROM payments").update();
        jdbc.sql("DELETE FROM order_items").update();
        jdbc.sql("DELETE FROM user_event_allowance").update();
        jdbc.sql("UPDATE event_seats SET status = 'AVAILABLE', hold_id = NULL, order_id = NULL, version = 0").update();
        jdbc.sql("DELETE FROM orders").update();
        jdbc.sql("DELETE FROM holds").update();
        consumer.ensureGroup();
        load();
    }
}
