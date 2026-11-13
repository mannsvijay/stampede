package com.stampede.inventory.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.stampede.inventory.seat.SeatStatus;
import com.stampede.inventory.strategy.RedisReconciler.Repairs;
import com.stampede.testsupport.ExpectInvariantViolations;
import com.stampede.testsupport.TestRedis;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** M2.2b: each reconciler rule (R0 to R4), and the guards that keep it from racing the consumer. */
class RedisReconcilerTest extends RedisPersistenceTestSupport {

    @Test
    void r0LoadsAnEventRedisDoesNotHaveAndDoesNothingElseThatPass() {
        UUID holdId = holdInPostgresOnly(USER, FIVE_MINUTES, SEATS.get(0));

        Repairs repairs = reconciler.reconcileOnce();

        assertThat(repairs).isEqualTo(new Repairs(1, 0, 0, 0, 0, 0, 0));
        assertThat(store.liveHold(EVENT, holdId)).isPresent();
        assertThat(reconciler.reconcileOnce()).isEqualTo(Repairs.NONE);
    }

    @Test
    void r1RollsForwardALiveRedisHoldWhoseEntryWasLostButOnlyAfterTheMinimumAge() {
        load();
        UUID holdId = hold(USER, FIVE_MINUTES, SEATS.get(0), SEATS.get(1));
        redis.delete(store.keys().stream());   // the entry is gone (lost, trimmed or dead-lettered)

        assertThat(reconciler.reconcileOnce().rolledForward()).as("younger than min-age: the consumer's job")
                .isZero();
        assertThat(holdRow(holdId)).isEmpty();

        await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(100))
                .until(() -> reconciler.reconcileOnce().rolledForward() == 1);

        assertThat(holdStatus(holdId)).isEqualTo("ACTIVE");
        assertThat(seat(SEATS.get(1)).holdId()).isEqualTo(holdId);
        assertThat(allowance(USER)).isEqualTo(2);
        assertThat(counter(RedisStreamMetrics.REPAIRS, "rule", "R1_roll_forward")).isEqualTo(1);
        assertThat(reconciler.reconcileOnce()).isEqualTo(Repairs.NONE);
        assertThat(violations()).isEmpty();
    }

    @Test
    void r2ClosesAPostgresHoldRedisNoLongerHasAsReleasedBeforeItsTime() {
        load();
        UUID holdId = hold(USER, FIVE_MINUTES, SEATS.get(0));
        drain();
        // Redis lost this one hold (partial loss) or its RELEASED entry never arrived.
        redis.delete(List.of(store.keys().hold(EVENT, holdId), store.keys().seat(EVENT, SEATS.get(0))));

        assertThat(reconciler.reconcileOnce().closed()).isEqualTo(1);

        assertThat(holdStatus(holdId)).isEqualTo("RELEASED");
        assertThat(seat(SEATS.get(0)).status()).isEqualTo(SeatStatus.AVAILABLE);
        assertThat(allowance(USER)).isZero();
        assertThat(violations()).isEmpty();
    }

    @Test
    void r2ClosesAHoldWhoseTimeRanOutInRedisAsExpired() {
        load();
        UUID holdId = hold(USER, ONE_SECOND, SEATS.get(0));
        drain();
        waitForRedisExpiry(holdId);

        assertThat(reconciler.reconcileOnce().closed()).isEqualTo(1);

        assertThat(holdStatus(holdId)).isEqualTo("EXPIRED");
        assertThat(violations()).isEmpty();
    }

    @Test
    void r2NeverClosesHoldsOfAnEventRedisHasLost() {
        load();
        UUID holdId = hold(USER, FIVE_MINUTES, SEATS.get(0));
        drain();
        redis.delete(store.keys().meta(EVENT));   // "not loaded": an absent hold means nothing now

        Repairs repairs = reconciler.reconcile(EVENT);

        assertThat(repairs.closed()).isZero();
        assertThat(repairs.loaded()).isEqualTo(1);
        assertThat(holdStatus(holdId)).isEqualTo("ACTIVE");
        assertThat(store.liveHold(EVENT, holdId)).isPresent();
    }

    @Test
    void r2StopsThePassAndClosesNothingWhenRedisIsReloadedDuringIt() {
        load();
        UUID holdId = hold(USER, FIVE_MINUTES, SEATS.get(0));
        drain();
        // Redis lost this one hold, so R2 is about to close it...
        redis.delete(List.of(store.keys().hold(EVENT, holdId), store.keys().seat(EVENT, SEATS.get(0))));
        // ...when Redis is wiped and reloaded from Postgres, which restores the hold.
        reconciler.beforeEpochCheck(() -> {
            TestRedis.deleteKeys(redis, PREFIX);
            loader.ensureLoaded(EVENT);
        });
        Repairs repairs;
        try {
            repairs = reconciler.reconcile(EVENT);
        } finally {
            reconciler.beforeEpochCheck(() -> { });
        }

        assertThat(repairs.closed()).isZero();
        assertThat(holdStatus(holdId)).isEqualTo("ACTIVE");
        assertThat(store.liveHold(EVENT, holdId)).as("restored by the reload").isPresent();
        assertThat(seat(SEATS.get(0)).holdId()).isEqualTo(holdId);
        assertThat(violations()).isEmpty();
    }

    @Test
    @ExpectInvariantViolations(rules = {"O1", "O5", "O7"},
            reason = "Inventory-only test of a sale (D-017 step 3): the order is a bare PENDING row that booking's step 4 never marks PAID")
    void r4FinishesInRedisASaleThatPostgresCommittedButRedisNeverRecorded() {
        load();
        UUID holdId = hold(USER, FIVE_MINUTES, SEATS.get(0), SEATS.get(1));
        drain();
        UUID orderId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO orders (id, user_id, event_id, hold_id, status, total_paise, saga_state)
                VALUES (?, ?, ?, ?, 'PENDING', 300000, 'ORDER_CREATED')
                """).params(orderId, USER, EVENT, holdId).update();
        // Steps 1 and 2 of a Redis-first sale ran (pin, Postgres sale); step 3 (sold.lua) never did.
        store.pin(EVENT, holdId);
        assertThat(closing.sell(EVENT, holdId, orderId)).isInstanceOf(SellOutcome.Sold.class);

        Repairs repairs = reconciler.reconcileOnce();

        // Finished, not released: releasing would free sold seats in Redis.
        assertThat(repairs).isEqualTo(new Repairs(0, 0, 0, 1, 0, 0, 0));
        assertThat(counter(RedisStreamMetrics.REPAIRS, "rule", "R4_finish_sale")).isEqualTo(1);
        assertThat(store.seatValue(EVENT, SEATS.get(0))).startsWith("S:" + orderId + ":");
        assertThat(store.seatValue(EVENT, SEATS.get(1))).startsWith("S:" + orderId + ":");
        assertThat(store.liveHold(EVENT, holdId)).isEmpty();
        assertThat(store.used(EVENT, USER)).isEqualTo(2);
        assertThat(reconciler.reconcileOnce()).isEqualTo(Repairs.NONE);
    }

    @Test
    void r3RestoresSoldMarkersAndTheBuyersCapAndReleasesARedisHoldOnASoldSeat() {
        load();
        UUID holdId = hold(USER, FIVE_MINUTES, SEATS.get(0));   // Redis doesn't know the seat is sold...
        UUID orderId = sellDirectly(OTHER_USER, SEATS.get(0));  // ...because the sale was written to Postgres only

        Repairs repairs = reconciler.reconcileOnce();

        assertThat(repairs.soldMarkers()).isEqualTo(1);
        assertThat(repairs.soldMembers()).isEqualTo(1);
        assertThat(repairs.releasedInRedis()).isEqualTo(1);
        assertThat(store.seatValue(EVENT, SEATS.get(0))).startsWith("S:" + orderId + ":");
        assertThat(store.liveHold(EVENT, holdId)).isEmpty();
        assertThat(store.used(EVENT, OTHER_USER)).isEqualTo(1);
        assertThat(store.used(EVENT, USER)).isZero();

        // The hold's own entries then arrive: it is over in Redis, so it is recorded as over.
        drain();
        assertThat(holdStatus(holdId)).isEqualTo("RELEASED");
        assertThat(reconciler.reconcileOnce()).isEqualTo(Repairs.NONE);
        assertThat(violations()).isEmpty();
    }

    @Test
    void r4ReleasesARedisHoldThatPostgresHasClosed() {
        load();
        UUID holdId = hold(USER, FIVE_MINUTES, SEATS.get(0));
        drain();
        // Closed in Postgres only (as if Redis missed it): Postgres wins.
        assertThat(closing.release(EVENT, holdId, ReleaseReason.RELEASED)).isInstanceOf(ReleaseOutcome.Released.class);

        assertThat(reconciler.reconcileOnce().releasedInRedis()).isEqualTo(1);

        assertThat(store.liveHold(EVENT, holdId)).isEmpty();
        assertThat(store.seatValue(EVENT, SEATS.get(0))).isNull();
        assertThat(store.used(EVENT, USER)).isZero();
        drain();   // the RELEASED entry that R4 wrote changes nothing
        assertThat(holdStatus(holdId)).isEqualTo("RELEASED");
        assertThat(violations()).isEmpty();
    }

    @Test
    void aConsistentEventNeedsNoRepair() {
        sellDirectly(USERS.get(2), SEATS.get(2));   // before the load, so the loader writes its marker
        load();
        hold(USER, FIVE_MINUTES, SEATS.get(0));
        UUID released = hold(OTHER_USER, FIVE_MINUTES, SEATS.get(1));
        releaseInRedis(released);
        drain();

        assertThat(reconciler.reconcileOnce()).isEqualTo(Repairs.NONE);
        assertThat(violations()).isEmpty();
    }
}
