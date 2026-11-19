package com.stampede.inventory.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.stampede.inventory.api.EventStrategy;
import com.stampede.inventory.api.SwitchResult;
import com.stampede.inventory.api.SwitchResult.Reason;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * M2.3a, D-023 (option A): switching an event's strategy only while it takes no holds and nothing is
 * live, against real Postgres and Redis. Every refusal leaves the event exactly as it was (strategy,
 * Redis gate, no audit call); every clean switch leaves the strict invariants green, I10 included.
 * <p>
 * The event of {@link RedisPersistenceTestSupport} starts as REDIS_FIRST and on sale; tests move its
 * drop into the future ({@link #offSale()}) before a switch, as a benchmark run between two drops would.
 */
class StrategySwitcherTest extends RedisPersistenceTestSupport {

    @Autowired
    StrategySwitcher switcher;

    @Autowired
    PessimisticInventoryStrategy pessimistic;

    @Autowired
    OptimisticInventoryStrategy optimistic;

    @Autowired
    RedisFirstInventoryStrategy redisFirst;

    /** Every audit call the switcher made, as "from->to". */
    private final List<String> audited = new ArrayList<>();

    private ExecutorService threads;

    @BeforeEach
    void startThreads() {
        audited.clear();
        threads = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void stopThreads() {
        threads.shutdownNow();
        switcher.whileLocked(() -> { });
    }

    // --- clean switches ----------------------------------------------------------------------------

    /**
     * All six directions in one cycle: REDIS_FIRST -> PESSIMISTIC -> OPTIMISTIC -> REDIS_FIRST ->
     * OPTIMISTIC -> PESSIMISTIC -> REDIS_FIRST. Before each switch the current strategy sells a seat and
     * holds and releases another while the event is on sale; then the sale closes, the holds have ended,
     * and the switch must succeed with every invariant strict (zero grace), I10 for the REDIS_FIRST legs.
     * After each switch the new strategy takes holds again, so a switch never leaves an event unusable.
     */
    @Test
    void cleanSwitchesInAllSixDirectionsKeepEveryInvariant() {
        load();
        List<StrategyKind> cycle = List.of(StrategyKind.PESSIMISTIC, StrategyKind.OPTIMISTIC,
                StrategyKind.REDIS_FIRST, StrategyKind.OPTIMISTIC, StrategyKind.PESSIMISTIC, StrategyKind.REDIS_FIRST);
        StrategyKind current = StrategyKind.REDIS_FIRST;
        int seat = 0;
        int leg = 0;
        for (StrategyKind next : cycle) {
            onSale();
            // A different buyer per leg: bought seats count towards the per-user limit (4) for good.
            useAndEndHolds(current, USERS.get(leg++), SEATS.get(seat++), SEATS.get(seat++));
            offSale();
            assertThat(violations()).as("before %s -> %s", current, next).isEmpty();

            SwitchResult result = switchTo(next);

            assertThat(result).isEqualTo(new SwitchResult.Switched(api(current), api(next)));
            assertThat(strategyInPostgres()).isEqualTo(next.name());
            assertThat(violations()).as("after %s -> %s", current, next).isEmpty();
            if (next == StrategyKind.REDIS_FIRST) {
                assertThat(store.isLoaded(EVENT)).as("loaded right after the switch").isTrue();
                assertThat(store.isClosed(EVENT)).as("gate open").isFalse();
            } else if (current == StrategyKind.REDIS_FIRST) {
                assertThat(eventKeys()).as("every key deleted except the gate")
                        .containsExactly(store.keys().closed(EVENT));
            }
            current = next;
        }
        assertThat(audited).containsExactly("REDIS_FIRST->PESSIMISTIC", "PESSIMISTIC->OPTIMISTIC",
                "OPTIMISTIC->REDIS_FIRST", "REDIS_FIRST->OPTIMISTIC", "OPTIMISTIC->PESSIMISTIC",
                "PESSIMISTIC->REDIS_FIRST");
        // The last leg reloaded the event from Postgres: every seat sold on the way is an S: marker.
        assertThat(jdbc.sql("SELECT count(*) FROM event_seats WHERE event_id = ? AND status = 'SOLD'")
                .param(EVENT).query(Long.class).single()).isEqualTo(6);
        onSale();
        assertThat(redisFirst.hold(new HoldRequest(UUID.randomUUID(), EVENT, USER, List.of(SEATS.get(20)),
                Duration.ofMinutes(5)))).isInstanceOf(HoldOutcome.Held.class);
        drain();
    }

    @Test
    void switchingToTheStrategyTheEventHasChangesNothing() {
        offSale();

        assertThat(switchTo(StrategyKind.REDIS_FIRST))
                .isEqualTo(new SwitchResult.Unchanged(EventStrategy.REDIS_FIRST));
        assertThat(audited).isEmpty();
    }

    @Test
    void anUnknownEventIsNotFound() {
        assertThat(switcher.switchStrategy(UUID.randomUUID(), EventStrategy.OPTIMISTIC, this::audit))
                .isInstanceOf(SwitchResult.EventNotFound.class);
        assertThat(audited).isEmpty();
    }

    /**
     * Keys left from an earlier REDIS_FIRST period (here: a stale meta with another limit, a seat key
     * naming a hold that no longer exists, and the gate of the switch away) would make load.lua answer
     * ALREADY_LOADED and keep them. The switch to REDIS_FIRST deletes them first and loads Postgres' state.
     */
    @Test
    void switchingToRedisFirstClearsStaleKeysFirst() {
        setStrategy(StrategyKind.PESSIMISTIC);
        offSale();
        store.close(EVENT);
        store.loadEvent(EVENT, SEATS, 1);
        String staleSeat = store.keys().seat(EVENT, SEATS.get(3));
        redis.opsForValue().set(staleSeat, "H:" + UUID.randomUUID() + ":1");

        assertThat(switchTo(StrategyKind.REDIS_FIRST)).isInstanceOf(SwitchResult.Switched.class);

        assertThat(redis.opsForValue().get(staleSeat)).as("stale seat key gone").isNull();
        assertThat(redis.opsForHash().get(store.keys().meta(EVENT), "limit")).isEqualTo(Integer.toString(LIMIT));
        assertThat(redis.opsForHash().get(store.keys().meta(EVENT), "loadedMs")).as("a real load").isNotNull();
        assertThat(store.isClosed(EVENT)).isFalse();
        assertThat(violations()).isEmpty();
    }

    // --- refusals: nothing changes -----------------------------------------------------------------

    @Test
    void anEventOnSaleIsRefused() {
        load();

        assertRefused(StrategyKind.PESSIMISTIC, Reason.ON_SALE, StrategyKind.REDIS_FIRST);
    }

    @Test
    void anEndedEventIsRefused() {
        load();
        jdbc.sql("UPDATE events SET status = 'ENDED' WHERE id = ?").param(EVENT).update();

        assertRefused(StrategyKind.OPTIMISTIC, Reason.EVENT_ENDED, StrategyKind.REDIS_FIRST);
    }

    @Test
    void anActiveHoldInPostgresIsRefused() {
        setStrategy(StrategyKind.PESSIMISTIC);
        UUID holdId = UUID.randomUUID();
        assertThat(pessimistic.hold(new HoldRequest(holdId, EVENT, USER, List.of(SEATS.get(0)), Duration.ofMinutes(5))))
                .isInstanceOf(HoldOutcome.Held.class);
        offSale();

        assertRefused(StrategyKind.REDIS_FIRST, Reason.LIVE_HOLDS, StrategyKind.PESSIMISTIC);
        assertThat(store.isLoaded(EVENT)).as("nothing loaded into Redis").isFalse();
    }

    @Test
    void aHoldOnlyRedisHasIsRefused() {
        load();
        hold(USER, Duration.ofMinutes(5), SEATS.get(0));   // in Redis only: the consumer hasn't run
        offSale();

        assertRefused(StrategyKind.PESSIMISTIC, Reason.LIVE_REDIS_HOLDS, StrategyKind.REDIS_FIRST);

        drain();   // persist it, so the after-test check sees Redis and Postgres agree
    }

    @Test
    void entriesTheConsumerHasNotReadYetAreRefused() {
        load();
        releaseInRedis(hold(USER, Duration.ofMinutes(5), SEATS.get(0)));   // HELD + RELEASED, neither read
        offSale();

        assertRefused(StrategyKind.OPTIMISTIC, Reason.UNAPPLIED_STREAM_ENTRIES, StrategyKind.REDIS_FIRST);

        drain();
    }

    @Test
    void entriesReadButNotAcknowledgedAreRefused() {
        load();
        releaseInRedis(hold(USER, Duration.ofMinutes(5), SEATS.get(0)));
        assertThat(readWithoutAck("crashed", 10)).hasSize(2);   // delivered, pending: a consumer that died
        offSale();

        assertRefused(StrategyKind.PESSIMISTIC, Reason.UNAPPLIED_STREAM_ENTRIES, StrategyKind.REDIS_FIRST);

        drain();
        assertThat(switchTo(StrategyKind.PESSIMISTIC)).as("allowed once applied").isInstanceOf(SwitchResult.Switched.class);
    }

    @Test
    void entriesOfOtherEventsDoNotBlockTheSwitch() {
        load();
        redis.opsForStream().add(store.keys().stream(), Map.of("type", "HELD", "event", UUID.randomUUID().toString(),
                "hold", UUID.randomUUID().toString()));
        offSale();

        assertThat(consumer.hasUnappliedEntries(EVENT)).isFalse();
        assertThat(switchTo(StrategyKind.PESSIMISTIC)).isInstanceOf(SwitchResult.Switched.class);
        // The next test's fixture deletes the stream with the rest of this context's keys.
    }

    /** A second switch while the first holds its locks: refused at once, the first one finishes. */
    @Test
    void aSecondSwitchWhileOneIsRunningIsRefused() {
        load();
        offSale();
        AtomicReference<SwitchResult> second = new AtomicReference<>();
        switcher.whileLocked(() -> second.set(
                switcher.switchStrategy(EVENT, EventStrategy.OPTIMISTIC, this::audit)));

        assertThat(switchTo(StrategyKind.PESSIMISTIC)).isInstanceOf(SwitchResult.Switched.class);

        assertThat(second.get()).isEqualTo(new SwitchResult.Refused(Reason.SWITCH_IN_PROGRESS, EventStrategy.REDIS_FIRST));
        assertThat(audited).containsExactly("REDIS_FIRST->PESSIMISTIC");
        assertThat(strategyInPostgres()).isEqualTo("PESSIMISTIC");
    }

    // --- the consumer guard ------------------------------------------------------------------------

    /**
     * An entry whose event is no longer REDIS_FIRST is acknowledged with nothing written. A switch can't
     * leave one behind (it refuses while entries are unapplied); this changes the strategy behind the
     * switch's back to reach the guard.
     */
    @Test
    void theConsumerAppliesNothingForAnEventThatIsNoLongerRedisFirst() {
        load();
        UUID holdId = hold(USER, Duration.ofMinutes(5), SEATS.get(0));
        setStrategy(StrategyKind.PESSIMISTIC);

        drain();

        assertThat(counter("stampede.inventory.redis.applied", "type", "held", "result", "not_redis_first")).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM holds WHERE id = ?").param(holdId).query(Long.class).single()).isZero();
        assertThat(pendingCount()).as("acknowledged").isZero();
    }

    // --- in flight ---------------------------------------------------------------------------------

    /**
     * A hold that holds a seat lock and then inserts its hold row while the switch waits for that seat.
     * The insert checks its foreign key with FOR KEY SHARE on the event row; the switch holds that row
     * with FOR NO KEY UPDATE, which doesn't conflict, so the hold commits (no deadlock) and the switch,
     * once it has the seat, sees the live hold and refuses. With FOR UPDATE this deadlocked.
     */
    @Test
    void aHoldCommittingWhileTheSwitchWaitsForItsSeatIsSeenAndNoDeadlock() throws Exception {
        setStrategy(StrategyKind.PESSIMISTIC);
        offSale();
        UUID seat = SEATS.get(0);
        UUID holdId = UUID.randomUUID();
        try (Connection buyer = dataSource.getConnection()) {
            buyer.setAutoCommit(false);
            exec(buyer, "SELECT 1 FROM event_seats WHERE event_id = ? AND seat_id = ? FOR UPDATE", EVENT, seat);

            Future<SwitchResult> pending = threads.submit(() -> switchTo(StrategyKind.OPTIMISTIC));
            waitUntilOneSessionWaitsForALock();

            exec(buyer, """
                    INSERT INTO holds (id, event_id, user_id, seat_ids, expires_at, status)
                    VALUES (?, ?, ?, ARRAY[?]::uuid[], now() + interval '5 minutes', 'ACTIVE')
                    """, holdId, EVENT, USER, seat);
            exec(buyer, "UPDATE event_seats SET status = 'HELD', hold_id = ?, version = version + 1 "
                    + "WHERE event_id = ? AND seat_id = ?", holdId, EVENT, seat);
            exec(buyer, "INSERT INTO user_event_allowance (event_id, user_id, used) VALUES (?, ?, 1)", EVENT, USER);
            buyer.commit();

            assertThat(pending.get(10, TimeUnit.SECONDS))
                    .isEqualTo(new SwitchResult.Refused(Reason.LIVE_HOLDS, EventStrategy.PESSIMISTIC));
        }
        assertThat(strategyInPostgres()).isEqualTo("PESSIMISTIC");
        assertThat(audited).isEmpty();
    }

    /**
     * The switch locks every seat of the event to take turns with holds (D-023), not with order creation
     * or ticket issuing: their foreign-key checks take {@code FOR KEY SHARE} on seats, which the switch's
     * {@code FOR NO KEY UPDATE} doesn't conflict with (D-015). With {@code FOR UPDATE} it waited for them,
     * and a ticket insert going in another seat order could deadlock with it (M2.4a).
     */
    @Test
    void theSwitchDoesNotWaitForAForeignKeyCheckOnASeat() throws Exception {
        setStrategy(StrategyKind.PESSIMISTIC);
        offSale();
        try (Connection checkout = foreignKeyCheckOn(SEATS.get(0))) {
            SwitchResult result = finishesWithoutWaitingForALock(threads.submit(() -> switchTo(StrategyKind.OPTIMISTIC)));

            assertThat(result).isEqualTo(new SwitchResult.Switched(EventStrategy.PESSIMISTIC, EventStrategy.OPTIMISTIC));
            checkout.rollback();
        }
        assertThat(strategyInPostgres()).isEqualTo("OPTIMISTIC");
    }

    // --- helpers -----------------------------------------------------------------------------------

    private void assertRefused(StrategyKind target, Reason reason, StrategyKind unchanged) {
        boolean wasClosed = store.isClosed(EVENT);

        assertThat(switchTo(target)).isEqualTo(new SwitchResult.Refused(reason, api(unchanged)));

        assertThat(strategyInPostgres()).isEqualTo(unchanged.name());
        assertThat(audited).as("no audit row for a refusal").isEmpty();
        assertThat(store.isClosed(EVENT)).as("the gate is as it was").isEqualTo(wasClosed);
    }

    /** One hold sold to {@code buyer} (a finished order) and one hold of USER released, through the strategy. */
    private void useAndEndHolds(StrategyKind kind, UUID buyer, UUID soldSeat, UUID releasedSeat) {
        InventoryStrategy strategy = strategy(kind);
        UUID released = UUID.randomUUID();
        assertThat(strategy.hold(new HoldRequest(released, EVENT, USER, List.of(releasedSeat), Duration.ofMinutes(5))))
                .isInstanceOf(HoldOutcome.Held.class);
        UUID sold = UUID.randomUUID();
        assertThat(strategy.hold(new HoldRequest(sold, EVENT, buyer, List.of(soldSeat), Duration.ofMinutes(5))))
                .isInstanceOf(HoldOutcome.Held.class);
        drain();   // REDIS_FIRST: the holds reach Postgres (checkout's catch-up would do it); the order needs the row
        assertThat(strategy.release(EVENT, released, ReleaseReason.RELEASED)).isInstanceOf(ReleaseOutcome.Released.class);
        UUID orderId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO orders (id, user_id, event_id, hold_id, status, total_paise, saga_state)
                VALUES (?, ?, ?, ?, 'PENDING', 150000, 'ORDER_CREATED')
                """).params(orderId, buyer, EVENT, sold).update();
        assertThat(strategy.sell(EVENT, sold, orderId)).isInstanceOf(SellOutcome.Sold.class);
        completeOrder(orderId, buyer, soldSeat);
        drain();   // REDIS_FIRST: every entry applied (both are no-ops by now); nothing for the others
    }

    /** A finished checkout: item, payment, ticket, PAID. */
    private void completeOrder(UUID orderId, UUID buyer, UUID seat) {
        jdbc.sql("INSERT INTO order_items (order_id, event_id, seat_id, price_paise) VALUES (?, ?, ?, 150000)")
                .params(orderId, EVENT, seat).update();
        jdbc.sql("""
                INSERT INTO tickets (order_id, event_id, seat_id, holder_user_id, signature, status, key_id,
                                     section_code, row_label, seat_number)
                VALUES (?, ?, ?, ?, decode(repeat('00', 64), 'hex'), 'ISSUED', '0123abcd', 'A', 'A', ?)
                """).params(orderId, EVENT, seat, buyer, SEATS.indexOf(seat) + 1).update();
        jdbc.sql("INSERT INTO payments (order_id, status, amount_paise) VALUES (?, 'SUCCEEDED', 150000)")
                .param(orderId).update();
        jdbc.sql("UPDATE orders SET status = 'PAID', saga_state = 'TICKETS_ISSUED' WHERE id = ?")
                .param(orderId).update();
    }

    private SwitchResult switchTo(StrategyKind target) {
        return switcher.switchStrategy(EVENT, api(target), this::audit);
    }

    private void audit(UUID eventId, EventStrategy from, EventStrategy to) {
        assertThat(eventId).isEqualTo(EVENT);
        audited.add(from + "->" + to);
    }

    private InventoryStrategy strategy(StrategyKind kind) {
        return switch (kind) {
            case PESSIMISTIC -> pessimistic;
            case OPTIMISTIC -> optimistic;
            case REDIS_FIRST -> redisFirst;
        };
    }

    /** Sets the strategy directly, for a test that starts from a non-REDIS_FIRST event (nothing in Redis). */
    private void setStrategy(StrategyKind kind) {
        jdbc.sql("UPDATE events SET strategy = ? WHERE id = ?").params(kind.name(), EVENT).update();
    }

    /** The drop moves a day ahead: the event takes no holds (D-016). */
    private void offSale() {
        jdbc.sql("UPDATE events SET drop_at = now() + interval '1 day' WHERE id = ?").param(EVENT).update();
    }

    private void onSale() {
        jdbc.sql("UPDATE events SET drop_at = now() - interval '1 hour' WHERE id = ?").param(EVENT).update();
    }

    private String strategyInPostgres() {
        return jdbc.sql("SELECT strategy FROM events WHERE id = ?").param(EVENT).query(String.class).single();
    }

    private List<String> eventKeys() {
        List<String> keys = new ArrayList<>();
        try (var cursor = redis.scan(org.springframework.data.redis.core.ScanOptions.scanOptions()
                .match(store.keys().eventPrefix(EVENT) + "*").build())) {
            cursor.forEachRemaining(keys::add);
        }
        return keys;
    }

    private void waitUntilOneSessionWaitsForALock() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (jdbc.sql("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock'")
                    .query(Long.class).single() == 1) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("Expected one session waiting for a lock within 10 s");
    }

    private static void exec(Connection connection, String sql, Object... params) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                statement.setObject(i + 1, params[i]);
            }
            statement.execute();
        }
    }

    private static EventStrategy api(StrategyKind kind) {
        return EventStrategy.valueOf(kind.name());
    }
}
