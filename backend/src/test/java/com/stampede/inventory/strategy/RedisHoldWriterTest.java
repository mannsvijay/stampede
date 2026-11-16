package com.stampede.inventory.strategy;

import com.stampede.testsupport.ExpectInvariantViolations;
import static org.assertj.core.api.Assertions.assertThat;

import com.stampede.inventory.seat.SeatStatus;
import com.stampede.inventory.strategy.RedisHoldWriter.Applied;
import com.stampede.testsupport.TestRedis;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.stream.MapRecord;

/**
 * M2.2b: applying stream entries to Postgres. Out-of-order redelivery, holds that are already over in
 * Redis, closing an older hold first, Postgres winning conflicts, and expiry for the sweeper.
 */
class RedisHoldWriterTest extends RedisPersistenceTestSupport {

    @org.springframework.beans.factory.annotation.Autowired
    org.springframework.transaction.PlatformTransactionManager transactions;

    // ---- out of order: a released hold never comes back ----

    @Test
    void aHeldRedeliveredAfterItsReleasedWasAppliedLeavesTheHoldReleased() {
        load();
        UUID holdId = hold(USER, FIVE_MINUTES, SEATS.get(0), SEATS.get(1));
        releaseInRedis(holdId);
        List<MapRecord<String, Object, Object>> entries = readWithoutAck("a", 10);
        assertThat(entries).hasSize(2);
        StreamEntry held = entry(entries.get(0));
        StreamEntry released = entry(entries.get(1));
        assertThat(held.type()).isEqualTo(StreamEntry.Type.HELD);

        // The RELEASED is applied first (consumer "a" crashed before the HELD): Postgres has no row yet,
        // so the release writes the hold as already over.
        assertThat(writer.apply(released)).isEqualTo(Applied.STALE);
        assertThat(holdStatus(holdId)).isEqualTo("RELEASED");

        // The HELD arrives later (reclaimed): the row exists, so nothing changes.
        assertThat(writer.apply(held)).isEqualTo(Applied.DUPLICATE);
        assertThat(consumer.reclaimOnce("b")).isEqualTo(2);   // both again, through the consumer

        assertThat(holdStatus(holdId)).isEqualTo("RELEASED");
        assertThat(seat(SEATS.get(0))).isEqualTo(new SeatRow(SeatStatus.AVAILABLE, null, null, 0));
        assertThat(seat(SEATS.get(1))).isEqualTo(new SeatRow(SeatStatus.AVAILABLE, null, null, 0));
        assertThat(allowance(USER)).isZero();
        assertThat(pendingCount()).isZero();
        assertThat(violations()).isEmpty();
    }

    @Test
    void aHeldEvenWithoutItsReleasedIsNotPersistedAsActiveOnceRedisReleasedTheHold() {
        load();
        UUID holdId = hold(USER, FIVE_MINUTES, SEATS.get(0));
        StreamEntry held = entry(readWithoutAck("a", 1).get(0));
        releaseInRedis(holdId);   // its RELEASED entry is still unread

        assertThat(writer.apply(held)).isEqualTo(Applied.STALE);

        assertThat(holdStatus(holdId)).as("released before its time ran out").isEqualTo("RELEASED");
        assertThat(seat(SEATS.get(0)).status()).isEqualTo(SeatStatus.AVAILABLE);
        drain("b");
        assertThat(holdStatus(holdId)).isEqualTo("RELEASED");
        assertThat(violations()).isEmpty();
    }

    @Test
    void anOlderHeldReplayedAfterANewerHoldOnTheSameSeatIsRecordedAsExpired() {
        load();
        UUID older = hold(USER, ONE_SECOND, SEATS.get(0));
        StreamEntry olderHeld = entry(readWithoutAck("a", 1).get(0));   // "a" dies holding it
        waitForRedisExpiry(older);
        UUID newer = hold(OTHER_USER, FIVE_MINUTES, SEATS.get(0));
        consumer.pollOnce("b");
        assertThat(holdStatus(newer)).isEqualTo("ACTIVE");

        assertThat(writer.apply(olderHeld)).isEqualTo(Applied.STALE);

        assertThat(holdStatus(older)).isEqualTo("EXPIRED");
        assertThat(seat(SEATS.get(0)).holdId()).isEqualTo(newer);
        assertThat(allowance(USER)).isZero();
        assertThat(allowance(OTHER_USER)).isEqualTo(1);
        assertThat(violations()).isEmpty();
    }

    // ---- an older hold that Redis expired is closed first ----

    @Test
    void aNewHoldOnASeatPostgresStillShowsHeldByAnExpiredHoldClosesThatHoldFirst() {
        load();
        UUID older = hold(USER, ONE_SECOND, SEATS.get(0), SEATS.get(1));
        drain();
        waitForRedisExpiry(older);   // gone in Redis; the sweeper (off in tests) never closed it
        assertThat(holdStatus(older)).isEqualTo("ACTIVE");

        UUID newer = hold(OTHER_USER, FIVE_MINUTES, SEATS.get(1));
        drain();

        assertThat(holdStatus(older)).isEqualTo("EXPIRED");
        assertThat(holdStatus(newer)).isEqualTo("ACTIVE");
        assertThat(seat(SEATS.get(0)).status()).isEqualTo(SeatStatus.AVAILABLE);
        assertThat(seat(SEATS.get(1)).holdId()).isEqualTo(newer);
        assertThat(allowance(USER)).isZero();
        assertThat(allowance(OTHER_USER)).isEqualTo(1);
        assertThat(violations()).isEmpty();
    }

    @Test
    void theCapInPostgresStopsCountingAHoldThatExpiredInRedis() {
        load();
        UUID older = hold(USER, ONE_SECOND, SEATS.get(0), SEATS.get(1), SEATS.get(2), SEATS.get(3));
        drain();
        waitForRedisExpiry(older);
        // Redis recounts the cap from live holds, so it allows four more; Postgres still counts four.
        UUID newer = hold(USER, FIVE_MINUTES, SEATS.get(4), SEATS.get(5), SEATS.get(6), SEATS.get(7));

        drain();

        assertThat(holdStatus(older)).isEqualTo("EXPIRED");
        assertThat(holdStatus(newer)).isEqualTo("ACTIVE");
        assertThat(allowance(USER)).isEqualTo(4);
        assertThat(violations()).isEmpty();
    }

    /**
     * M2.2c-2, found by the REDIS_FIRST harness (churn): several callers persist the same hold at the same
     * moment (catch-up from concurrent releases, the consumer, the reconciler). Exactly one inserts the
     * row; every other call must be a quiet DUPLICATE. With {@code ON CONFLICT (id)} a concurrent insert
     * could fail on the table's other unique constraint ({@code uq_holds_id_event}) with SQLState 23505.
     */
    @Test
    void manyCallersPersistingTheSameHoldAtOnceInsertItOnceWithoutErrors() throws Exception {
        load();
        int callers = 8;
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(callers);
        try {
            for (int round = 0; round < 30; round++) {
                UUID holdId = hold(USER, FIVE_MINUTES, SEATS.get(round % SEATS.size()));
                StreamEntry.HoldFacts facts = StreamEntry.HoldFacts.of(EVENT, store.liveHold(EVENT, holdId).orElseThrow());
                java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
                List<java.util.concurrent.Future<Applied>> results = new java.util.ArrayList<>();
                for (int c = 0; c < callers; c++) {
                    results.add(pool.submit(() -> {
                        start.await();
                        return writer.applyHeld(facts);
                    }));
                }
                start.countDown();
                List<Applied> applied = new java.util.ArrayList<>();
                for (java.util.concurrent.Future<Applied> result : results) {
                    applied.add(result.get(10, java.util.concurrent.TimeUnit.SECONDS));  // throws if a call threw
                }
                assertThat(applied).as("round %d", round).containsOnlyOnce(Applied.APPLIED)
                        .containsOnly(Applied.APPLIED, Applied.DUPLICATE);
                releaseInRedis(holdId);
                drain();
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(violations()).isEmpty();
    }

    // ---- Postgres wins ----

    @Test
    void aRedisHoldOnASeatPostgresHasSoldIsReleasedInRedisAndRecordedAsReleased() {
        // Redis doesn't know the seat is sold (loaded before the sale, or lost its marker).
        load();
        UUID orderId = sellDirectly(OTHER_USER, SEATS.get(0));
        UUID holdId = hold(USER, FIVE_MINUTES, SEATS.get(0), SEATS.get(1));

        drain();

        assertThat(holdStatus(holdId)).isEqualTo("RELEASED");
        assertThat(seat(SEATS.get(0)).status()).isEqualTo(SeatStatus.SOLD);
        assertThat(seat(SEATS.get(1))).isEqualTo(new SeatRow(SeatStatus.AVAILABLE, null, null, 0));
        assertThat(store.liveHold(EVENT, holdId)).isEmpty();
        assertThat(store.seatValue(EVENT, SEATS.get(0))).startsWith("S:" + orderId + ":");
        assertThat(store.seatValue(EVENT, SEATS.get(1))).isNull();
        assertThat(store.used(EVENT, USER)).isZero();
        assertThat(store.used(EVENT, OTHER_USER)).as("the buyer's cap counts the seat Redis didn't know was sold")
                .isEqualTo(1);
        assertThat(allowance(USER)).isZero();
        assertThat(counter(RedisStreamMetrics.APPLIED, "type", "held", "result", "conflict")).isEqualTo(1);
        assertThat(violations()).isEmpty();
    }

    @Test
    @ExpectInvariantViolations(rules = {"I10"},
            reason = "Deliberate drift (D-022 known limit): Redis lost a live hold's cap member, which nothing "
                    + "puts back; Postgres wins at persistence, and invariant 5 shows the undercount")
    void whenRedisUndercountsTheCapPostgresWins() {
        load();
        UUID first = hold(USER, FIVE_MINUTES, SEATS.get(0), SEATS.get(1), SEATS.get(2), SEATS.get(3));
        drain();
        // Redis loses the user's cap member (partial data loss) but still has the hold itself.
        redis.opsForZSet().remove(store.keys().user(EVENT, USER), first + ":4");
        UUID second = hold(USER, FIVE_MINUTES, SEATS.get(4));

        drain();

        assertThat(holdStatus(first)).as("live in Redis, so not closed").isEqualTo("ACTIVE");
        assertThat(holdStatus(second)).isEqualTo("RELEASED");
        assertThat(store.liveHold(EVENT, second)).isEmpty();
        assertThat(seat(SEATS.get(4)).status()).isEqualTo(SeatStatus.AVAILABLE);
        assertThat(allowance(USER)).isEqualTo(4);
        // Every Postgres rule holds; invariant 5 reports exactly the lost member, and nothing else.
        assertThat(violations()).singleElement().satisfies(v -> {
            assertThat(v.ruleId()).isEqualTo("I10");
            assertThat(v.detail()).isEqualTo("user " + USER + "'s cap counts [] in Redis but [" + first
                    + ":4] in Postgres (hold:seats)");
        });
    }

    @Test
    @ExpectInvariantViolations(rules = {"I10"},
            reason = "Deliberate drift (D-022): Redis is loaded with a seat set Postgres doesn't have, and the "
                    + "loader never rewrites a loaded event, so invariant 5 reports the seat set until a reload")
    void aSeatRedisHasButPostgresDoesNotIsAConflict() {
        UUID extra = id("not-in-postgres");
        store.loadEvent(EVENT, List.of(SEATS.get(0), extra), LIMIT);
        UUID holdId = hold(USER, FIVE_MINUTES, SEATS.get(0), extra);

        drain();

        assertThat(holdStatus(holdId)).isEqualTo("RELEASED");
        assertThat(seat(SEATS.get(0)).status()).isEqualTo(SeatStatus.AVAILABLE);
        assertThat(store.liveHold(EVENT, holdId)).isEmpty();
    }

    /**
     * Found by {@code RedisPersistenceConcurrencyTest} (line 145) on an overloaded machine: a HELD is
     * checked live in Redis, then its writer stalls before locking the seat. Meanwhile the buyer
     * releases the hold, another buyer takes the seat, and that newer hold is persisted. The stalled
     * writer then finds the seat held by a hold Redis really has. That is not a disagreement between
     * the stores: its own hold is simply over. It must be recorded as over (STALE), not as "Postgres
     * wins" (CONFLICT). Here the stall is a seat row lock held by the test, so it is deterministic.
     */
    @Test
    void aHeldWhoseHoldEndsWhileItWaitsForTheSeatLockIsStaleNotAConflict() throws Exception {
        load();
        UUID stalled = hold(USER, FIVE_MINUTES, SEATS.get(0));
        StreamEntry.HoldFacts stalledFacts = StreamEntry.HoldFacts.of(EVENT, store.liveHold(EVENT, stalled).orElseThrow());
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.CountDownLatch locked = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.CountDownLatch writeNewer = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.atomic.AtomicReference<UUID> newerRef = new java.util.concurrent.atomic.AtomicReference<>();
            // The "stall": a transaction holding the seat's row lock. While it holds it, it also persists
            // the newer hold, exactly as the consumer would (the seat row is the only lock that matters).
            java.util.concurrent.Future<?> blocker = pool.submit(() -> new org.springframework.transaction.support
                    .TransactionTemplate(transactions).executeWithoutResult(tx -> {
                jdbc.sql("SELECT seat_id FROM event_seats WHERE event_id = ? AND seat_id = ? FOR UPDATE")
                        .params(EVENT, SEATS.get(0)).query(UUID.class).single();
                locked.countDown();
                try {
                    assertThat(writeNewer.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException e) {
                    throw new IllegalStateException(e);
                }
                RedisHoldStore.LiveHold newer = store.liveHold(EVENT, newerRef.get()).orElseThrow();
                jdbc.sql("""
                        INSERT INTO holds (id, event_id, user_id, seat_ids, created_at, expires_at, status)
                        VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE')
                        """).params(newer.holdId(), EVENT, OTHER_USER, new UUID[] {SEATS.get(0)},
                        newer.createdAt().atOffset(java.time.ZoneOffset.UTC),
                        newer.expiresAt().atOffset(java.time.ZoneOffset.UTC)).update();
                jdbc.sql("UPDATE event_seats SET status = 'HELD', hold_id = ?, version = version + 1 "
                        + "WHERE event_id = ? AND seat_id = ?").params(newer.holdId(), EVENT, SEATS.get(0)).update();
                jdbc.sql("INSERT INTO user_event_allowance (event_id, user_id, used) VALUES (?, ?, 1)")
                        .params(EVENT, OTHER_USER).update();
            }));
            assertThat(locked.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

            // The writer passes its "still live in Redis" check, inserts the row and waits for the seat.
            java.util.concurrent.Future<Applied> stalledApply = pool.submit(() -> writer.applyHeld(stalledFacts));
            org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(10))
                    .pollInterval(java.time.Duration.ofMillis(20))
                    .until(() -> jdbc.sql("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' "
                            + "AND datname = current_database()").query(Long.class).single() == 1);

            releaseInRedis(stalled);
            UUID newer = hold(OTHER_USER, FIVE_MINUTES, SEATS.get(0));
            newerRef.set(newer);
            writeNewer.countDown();
            blocker.get(10, java.util.concurrent.TimeUnit.SECONDS);

            assertThat(stalledApply.get(10, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(Applied.STALE);
            drain();   // the stalled hold's RELEASED and the newer hold's HELD: both already handled

            assertThat(holdStatus(stalled)).isEqualTo("RELEASED");
            assertThat(holdStatus(newer)).isEqualTo("ACTIVE");
            assertThat(store.liveHold(EVENT, newer)).as("the newer hold is untouched in Redis").isPresent();
            assertThat(seat(SEATS.get(0)).holdId()).isEqualTo(newer);
            assertThat(allowance(USER)).isZero();
            assertThat(allowance(OTHER_USER)).isEqualTo(1);
            assertThat(counter(RedisStreamMetrics.APPLIED, "type", "held", "result", "conflict")).isZero();
            assertThat(violations()).isEmpty();
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * The cap form of the same race, and the worse one: the hold being persisted stays live, so a wrong
     * "Postgres wins" would release a good hold on both sides. The user's Postgres cap is full of holds
     * that never were live in Redis together: while the writer closes one hold Redis no longer has
     * (stalled on its row lock), another hold it had just seen live ends, and a newer one is persisted.
     * After the stall, Postgres counts the ended hold, the newer one and this one: over the cap. The
     * writer must close the ended hold too and insert, not declare a conflict.
     * <p>
     * Limit 4, two seats each. Postgres: {@code earlier} (live) and {@code gone} (released in Redis, its
     * RELEASED not applied). Redis: {@code earlier} + {@code mine}. The writer checks {@code earlier}
     * (live, kept) before {@code gone}: it checks the user's holds in id order ({@code ORDER BY id}), and
     * the test fixes both ids so that {@code earlier} sorts first. It then waits for {@code gone}'s row
     * lock; meanwhile {@code earlier} is released and {@code later} is taken and persisted.
     */
    @Test
    void aCapFullOfHoldsThatEndedWhileTheWriterWaitedIsClosedNotAConflict() throws Exception {
        load();
        UUID earlier = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID gone = UUID.fromString("00000000-0000-0000-0000-000000000002");
        assertThat(store.hold(new HoldRequest(earlier, EVENT, USER, List.of(SEATS.get(0), SEATS.get(1)), FIVE_MINUTES)))
                .isInstanceOf(HoldOutcome.Held.class);
        assertThat(store.hold(new HoldRequest(gone, EVENT, USER, List.of(SEATS.get(2), SEATS.get(3)), FIVE_MINUTES)))
                .isInstanceOf(HoldOutcome.Held.class);
        drain();
        releaseInRedis(gone);   // its RELEASED entry stays unapplied: Postgres still counts it
        UUID mine = hold(USER, FIVE_MINUTES, SEATS.get(4), SEATS.get(5));
        StreamEntry.HoldFacts mineFacts = StreamEntry.HoldFacts.of(EVENT, store.liveHold(EVENT, mine).orElseThrow());
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.CountDownLatch locked = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.CountDownLatch writeLater = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.atomic.AtomicReference<UUID> laterRef = new java.util.concurrent.atomic.AtomicReference<>();
            // The "stall": a transaction holding the row lock of the hold the writer will close. While it
            // holds it, it persists the later hold as the consumer would.
            java.util.concurrent.Future<?> blocker = pool.submit(() -> new org.springframework.transaction.support
                    .TransactionTemplate(transactions).executeWithoutResult(tx -> {
                jdbc.sql("SELECT id FROM holds WHERE id = ? FOR UPDATE").param(gone).query(UUID.class).single();
                locked.countDown();
                try {
                    assertThat(writeLater.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException e) {
                    throw new IllegalStateException(e);
                }
                RedisHoldStore.LiveHold later = store.liveHold(EVENT, laterRef.get()).orElseThrow();
                jdbc.sql("""
                        INSERT INTO holds (id, event_id, user_id, seat_ids, created_at, expires_at, status)
                        VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE')
                        """).params(later.holdId(), EVENT, USER, later.seatIds().toArray(UUID[]::new),
                        later.createdAt().atOffset(java.time.ZoneOffset.UTC),
                        later.expiresAt().atOffset(java.time.ZoneOffset.UTC)).update();
                for (UUID seatId : later.seatIds()) {
                    jdbc.sql("UPDATE event_seats SET status = 'HELD', hold_id = ?, version = version + 1 "
                            + "WHERE event_id = ? AND seat_id = ?").params(later.holdId(), EVENT, seatId).update();
                }
                jdbc.sql("UPDATE user_event_allowance SET used = used + 2 WHERE event_id = ? AND user_id = ?")
                        .params(EVENT, USER).update();
            }));
            assertThat(locked.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

            // Over the cap in Postgres (4 + 2); the writer keeps `earlier` (live) and waits to close `gone`.
            java.util.concurrent.Future<Applied> mineApply = pool.submit(() -> writer.applyHeld(mineFacts));
            org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(10))
                    .pollInterval(java.time.Duration.ofMillis(20))
                    .until(() -> jdbc.sql("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' "
                            + "AND datname = current_database()").query(Long.class).single() == 1);

            releaseInRedis(earlier);
            UUID later = hold(USER, FIVE_MINUTES, SEATS.get(6), SEATS.get(7));
            laterRef.set(later);
            writeLater.countDown();
            blocker.get(10, java.util.concurrent.TimeUnit.SECONDS);

            assertThat(mineApply.get(10, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(Applied.APPLIED);
            drain();   // earlier's and gone's RELEASED, later's HELD: all already handled

            assertThat(holdStatus(mine)).isEqualTo("ACTIVE");
            assertThat(store.liveHold(EVENT, mine)).as("the good hold is not released in Redis").isPresent();
            assertThat(holdStatus(later)).isEqualTo("ACTIVE");
            assertThat(holdStatus(earlier)).isEqualTo("RELEASED");
            assertThat(holdStatus(gone)).isEqualTo("RELEASED");
            assertThat(allowance(USER)).isEqualTo(4);
            assertThat(store.used(EVENT, USER)).isEqualTo(4);
            assertThat(counter(RedisStreamMetrics.APPLIED, "type", "held", "result", "conflict")).isZero();
            assertThat(violations()).isEmpty();
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * Found by the 1000-round {@code RedisPersistenceConcurrencyTest} (round 711, after M3): the writer
     * gave up after a pass in which <i>it</i> closed nothing, although the hold blocking it was gone from
     * Redis and another path (reconciler R2, the other consumer) closed it at that moment. Window 1: the
     * writer has the gone hold in its list, but the other path closes it first, so the writer's own close
     * returns NotActive.
     */
    @Test
    void aBlockingHoldAnotherPathClosesFirstStillCountsAsProgress() {
        assertTheCapRefillIsNotAConflict(false);
    }

    /**
     * Window 2 of the same race: the other path closes the gone hold after the writer's insert was
     * blocked but before the writer looked up which holds fill the cap, so the hold isn't in its list.
     */
    @Test
    void aBlockingHoldClosedBeforeTheWriterListsTheCapStillCountsAsProgress() {
        assertTheCapRefillIsNotAConflict(true);
    }

    /**
     * Limit 4, two seats each. Postgres: {@code first} (live in Redis) and {@code gone} (released in Redis,
     * its RELEASED not applied), so it is full. Redis: {@code first} + {@code mine}.
     * <ol>
     *   <li>Pass 1: blocked over the cap. {@code first} sorts first and is live (kept). Just before
     *       {@code gone} is closed, {@code first} ends in Redis and {@code later} is taken and persisted:
     *       the cap is full again ({@code first} + {@code later}).</li>
     *   <li>Pass 2: blocked again, by {@code first}, which Redis no longer has. Another path closes it,
     *       either after the writer listed it (window 1) or before (window 2).</li>
     *   <li>Pass 3 must insert: Postgres now counts {@code later} + {@code mine} = 4.</li>
     * </ol>
     */
    private void assertTheCapRefillIsNotAConflict(boolean closeBeforeListing) {
        load();
        UUID first = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID gone = UUID.fromString("00000000-0000-0000-0000-000000000002");
        assertThat(store.hold(new HoldRequest(first, EVENT, USER, List.of(SEATS.get(0), SEATS.get(1)), FIVE_MINUTES)))
                .isInstanceOf(HoldOutcome.Held.class);
        assertThat(store.hold(new HoldRequest(gone, EVENT, USER, List.of(SEATS.get(2), SEATS.get(3)), FIVE_MINUTES)))
                .isInstanceOf(HoldOutcome.Held.class);
        drain();
        releaseInRedis(gone);   // its RELEASED entry stays unapplied: Postgres still counts it
        UUID mine = hold(USER, FIVE_MINUTES, SEATS.get(4), SEATS.get(5));
        StreamEntry.HoldFacts mineFacts = StreamEntry.HoldFacts.of(EVENT, store.liveHold(EVENT, mine).orElseThrow());
        java.util.concurrent.atomic.AtomicReference<UUID> laterRef = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicInteger blockedCalls = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger closeCalls = new java.util.concurrent.atomic.AtomicInteger();
        Runnable otherPathClosesFirst = () -> assertThat(writer.closeGone(EVENT, first))
                .isInstanceOf(ReleaseOutcome.Released.class);
        writer.afterBlocked(() -> {
            if (blockedCalls.incrementAndGet() == 2 && closeBeforeListing) {
                otherPathClosesFirst.run();
            }
        });
        writer.beforeEpochCheck(() -> {
            int call = closeCalls.incrementAndGet();
            if (call == 1) {
                // Pass 1, about to close `gone`: `first` ends and `later` fills the cap again.
                releaseInRedis(first);
                UUID later = hold(USER, FIVE_MINUTES, SEATS.get(6), SEATS.get(7));
                laterRef.set(later);
                persistActiveDirectly(later);
            } else if (call == 2 && !closeBeforeListing) {
                otherPathClosesFirst.run();
            }
        });
        Applied applied;
        try {
            applied = writer.applyHeld(mineFacts);
        } finally {
            writer.afterBlocked(() -> { });
            writer.beforeEpochCheck(() -> { });
        }
        UUID later = laterRef.get();
        drain();   // first's and gone's RELEASED, later's HELD: all already handled

        assertThat(applied).isEqualTo(Applied.APPLIED);
        assertThat(holdStatus(mine)).isEqualTo("ACTIVE");
        assertThat(store.liveHold(EVENT, mine)).as("the good hold is not released in Redis").isPresent();
        assertThat(holdStatus(later)).isEqualTo("ACTIVE");
        assertThat(holdStatus(first)).isEqualTo("RELEASED");
        assertThat(holdStatus(gone)).isEqualTo("RELEASED");
        assertThat(allowance(USER)).isEqualTo(4);
        assertThat(store.used(EVENT, USER)).isEqualTo(4);
        assertThat(counter(RedisStreamMetrics.APPLIED, "type", "held", "result", "conflict")).isZero();
        assertThat(violations()).isEmpty();
    }

    /** Persists a live Redis hold as ACTIVE the way the consumer would, without the writer (no hooks). */
    private void persistActiveDirectly(UUID holdId) {
        RedisHoldStore.LiveHold live = store.liveHold(EVENT, holdId).orElseThrow();
        jdbc.sql("""
                INSERT INTO holds (id, event_id, user_id, seat_ids, created_at, expires_at, status)
                VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE')
                """).params(live.holdId(), EVENT, USER, live.seatIds().toArray(UUID[]::new),
                live.createdAt().atOffset(java.time.ZoneOffset.UTC),
                live.expiresAt().atOffset(java.time.ZoneOffset.UTC)).update();
        for (UUID seatId : live.seatIds()) {
            jdbc.sql("UPDATE event_seats SET status = 'HELD', hold_id = ?, version = version + 1 "
                    + "WHERE event_id = ? AND seat_id = ?").params(live.holdId(), EVENT, seatId).update();
        }
        jdbc.sql("UPDATE user_event_allowance SET used = used + ? WHERE event_id = ? AND user_id = ?")
                .params(live.seatIds().size(), EVENT, USER).update();
    }

    // ---- expiry, as the sweeper will call it (M2.2c) ----

    @Test
    void expiryAsksRedisFirstAndClosesOnlyWhatRedisNoLongerHas() {
        load();
        UUID holdId = hold(USER, ONE_SECOND, SEATS.get(0));
        drain();

        assertThat(writer.expire(EVENT, holdId)).isInstanceOf(ReleaseOutcome.NotExpired.class);
        assertThat(holdStatus(holdId)).isEqualTo("ACTIVE");

        waitForRedisExpiry(holdId);
        assertThat(writer.expire(EVENT, holdId)).isEqualTo(new ReleaseOutcome.Released(List.of(SEATS.get(0))));
        assertThat(holdStatus(holdId)).isEqualTo("EXPIRED");
        assertThat(seat(SEATS.get(0)).status()).isEqualTo(SeatStatus.AVAILABLE);
        assertThat(allowance(USER)).isZero();

        assertThat(writer.expire(EVENT, holdId)).isEqualTo(new ReleaseOutcome.NotActive("EXPIRED"));
        assertThat(writer.expire(EVENT, UUID.randomUUID())).isEqualTo(new ReleaseOutcome.NotFound());
        assertThat(violations()).isEmpty();
    }

    @Test
    void expiryLeavesHoldsAloneWhileRedisDoesNotHaveTheEventLoaded() {
        load();
        UUID holdId = hold(USER, FIVE_MINUTES, SEATS.get(0));
        drain();
        TestRedis.deleteKeys(redis, PREFIX);   // Redis restarted without its data

        assertThat(writer.expire(EVENT, holdId)).isInstanceOf(ReleaseOutcome.NotExpired.class);
        assertThat(holdStatus(holdId)).isEqualTo("ACTIVE");
    }

    // ---- the load-epoch guard on "close older holds Redis no longer has" (M2.2c) ----

    @Test
    void aReloadWhileClosingAnOlderHoldStopsTheCloseAndTheReloadedHoldSurvives() {
        load();
        UUID older = hold(USER, FIVE_MINUTES, SEATS.get(0));
        drain();
        // Redis loses the older hold without an entry (partial loss), so a newer hold takes its seat.
        redis.delete(List.of(store.keys().hold(EVENT, older), store.keys().seat(EVENT, SEATS.get(0))));
        UUID newer = hold(OTHER_USER, FIVE_MINUTES, SEATS.get(0));
        StreamEntry.HoldFacts newerFacts = StreamEntry.HoldFacts.of(EVENT, store.liveHold(EVENT, newer).orElseThrow());
        // At the one moment the guard protects (after "Redis doesn't have the older hold", before the
        // close), Redis is wiped and reloaded from Postgres, which still has the older hold ACTIVE.
        writer.beforeEpochCheck(() -> {
            TestRedis.deleteKeys(redis, PREFIX);
            loader.ensureLoaded(EVENT);
        });
        Applied applied;
        try {
            applied = writer.applyHeld(newerFacts);
        } finally {
            writer.beforeEpochCheck(() -> { });
        }

        // Without the guard the older hold would be closed although the reload just restored it.
        assertThat(holdStatus(older)).isEqualTo("ACTIVE");
        assertThat(store.liveHold(EVENT, older)).isPresent();
        assertThat(seat(SEATS.get(0)).holdId()).isEqualTo(older);
        // The newer hold is still blocked after the reload: Postgres wins (D-022).
        assertThat(applied).isEqualTo(Applied.CONFLICT);
        assertThat(holdStatus(newer)).isEqualTo("RELEASED");
        assertThat(violations()).isEmpty();
    }

    // ---- lock-first expiry of a pinned hold (M2.2c) ----

    @Test
    void expiryFreesAPinnedHoldWhoseSaleNeverFinishedOnceItsTimeRanOut() {
        load();
        UUID holdId = hold(USER, ONE_SECOND, SEATS.get(0));
        drain();
        store.pin(EVENT, holdId);   // and then the process crashed before the Postgres sale

        assertThat(writer.expire(EVENT, holdId)).isInstanceOf(ReleaseOutcome.NotExpired.class);
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5))
                .pollInterval(java.time.Duration.ofMillis(50))
                .until(() -> store.nowMillis() > holdRow(holdId).orElseThrow().expiresAt().toEpochMilli() + 50);

        assertThat(writer.expire(EVENT, holdId)).isEqualTo(new ReleaseOutcome.Released(List.of(SEATS.get(0))));
        assertThat(holdStatus(holdId)).isEqualTo("EXPIRED");
        assertThat(store.liveHold(EVENT, holdId)).isEmpty();
        assertThat(store.seatValue(EVENT, SEATS.get(0))).isNull();
        assertThat(store.used(EVENT, USER)).isZero();
        assertThat(allowance(USER)).isZero();
        assertThat(violations()).isEmpty();
    }
}
