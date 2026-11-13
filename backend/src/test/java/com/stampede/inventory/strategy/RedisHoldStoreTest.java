package com.stampede.inventory.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.stampede.inventory.strategy.HoldOutcome.Held;
import com.stampede.inventory.strategy.HoldOutcome.Rejected;
import com.stampede.inventory.strategy.RedisHoldStore.LiveHold;
import com.stampede.inventory.strategy.RedisHoldStore.Release;
import com.stampede.testsupport.TestDatabases;
import com.stampede.testsupport.TestRedis;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.redis.DataRedisTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * M2.2a: the Redis hold store against Redis alone (no Postgres in this context). What hold.lua and
 * release.lua check and write: all-or-nothing, rejection precedence, the per-user cap, release, expiry
 * by TTL, and the stream entry per change.
 */
@DataRedisTest
@Import({RedisHoldStore.class, InventoryStrategyConfig.class})
class RedisHoldStoreTest {

    static final String PREFIX = "redis_hold_store";

    @DynamicPropertySource
    static void redis(DynamicPropertyRegistry registry) {
        TestDatabases.registerRedis(registry, PREFIX);
    }

    static final UUID USER = UUID.fromString("00000000-0000-4000-8000-0000000000a1");
    static final UUID OTHER_USER = UUID.fromString("00000000-0000-4000-8000-0000000000a2");
    static final UUID EVENT_A = UUID.fromString("00000000-0000-4000-8000-00000000000a");
    static final UUID EVENT_B = UUID.fromString("00000000-0000-4000-8000-00000000000b");
    static final UUID SEAT_1 = UUID.fromString("10000000-0000-4000-8000-000000000001");
    static final UUID SEAT_2 = UUID.fromString("90000000-0000-4000-8000-000000000002");
    static final UUID SEAT_3 = UUID.fromString("30000000-0000-4000-8000-000000000003");
    static final UUID SEAT_4 = UUID.fromString("c0000000-0000-4000-8000-000000000004");
    static final UUID SEAT_5 = UUID.fromString("50000000-0000-4000-8000-000000000005");
    static final UUID SEAT_B = UUID.fromString("b0000000-0000-4000-8000-00000000000b");
    static final Duration FIVE_MINUTES = Duration.ofMinutes(5);
    static final int LIMIT = 4;

    @Autowired
    RedisHoldStore store;

    @Autowired
    StringRedisTemplate redis;

    @BeforeEach
    void loadEvents() {
        TestRedis.deleteKeys(redis, PREFIX);
        store.loadEvent(EVENT_A, List.of(SEAT_1, SEAT_2, SEAT_3, SEAT_4, SEAT_5), LIMIT);
        store.loadEvent(EVENT_B, List.of(SEAT_B), LIMIT);
    }

    @AfterEach
    void deleteKeys() {
        TestRedis.deleteKeys(redis, PREFIX);
    }

    // ---- hold ----

    @Test
    void holdTakesEverySeatAndExpiresByRedisClock() {
        UUID holdId = UUID.randomUUID();
        long before = redisTimeMillis();

        HoldOutcome outcome = store.hold(request(holdId, USER, SEAT_1, SEAT_2));

        long after = redisTimeMillis();
        Held held = (Held) outcome;
        assertThat(held.holdId()).isEqualTo(holdId);
        assertThat(held.seatIds()).containsExactly(SEAT_1, SEAT_2);
        assertThat(held.expiresAt()).isBetween(
                Instant.ofEpochMilli(before).plus(FIVE_MINUTES), Instant.ofEpochMilli(after).plus(FIVE_MINUTES));

        assertThat(store.holderOf(EVENT_A, SEAT_1)).contains(holdId);
        assertThat(store.holderOf(EVENT_A, SEAT_2)).contains(holdId);
        assertThat(store.holderOf(EVENT_A, SEAT_3)).isEmpty();
        LiveHold live = store.liveHold(EVENT_A, holdId).orElseThrow();
        assertThat(live.userId()).isEqualTo(USER);
        assertThat(live.seatIds()).containsExactly(SEAT_1, SEAT_2);
        assertThat(live.expiresAt()).isEqualTo(held.expiresAt());
        assertThat(Duration.between(live.createdAt(), live.expiresAt())).isEqualTo(FIVE_MINUTES);
    }

    @Test
    void everyKeyOfAHoldExpiresAtTheSameMillisecond() {
        UUID holdId = UUID.randomUUID();
        Held held = (Held) store.hold(request(holdId, USER, SEAT_1, SEAT_2));

        RedisKeys keys = store.keys();
        long expected = held.expiresAt().toEpochMilli();
        assertThat(expireTimeMillis(keys.seat(EVENT_A, SEAT_1))).isEqualTo(expected);
        assertThat(expireTimeMillis(keys.seat(EVENT_A, SEAT_2))).isEqualTo(expected);
        assertThat(expireTimeMillis(keys.hold(EVENT_A, holdId))).isEqualTo(expected);
        assertThat(expireTimeMillis(keys.user(EVENT_A, USER))).isEqualTo(expected);
    }

    @Test
    void holdIsAllOrNothingWhenOneSeatIsTaken() {
        UUID first = UUID.randomUUID();
        store.hold(request(first, OTHER_USER, SEAT_2));
        List<String> keysBefore = sortedKeys();
        long entriesBefore = streamEntries().size();

        HoldOutcome outcome = store.hold(request(UUID.randomUUID(), USER, SEAT_1, SEAT_2, SEAT_3));

        Rejected rejected = (Rejected) outcome;
        assertThat(rejected.reason()).isEqualTo(HoldRejection.SEAT_TAKEN);
        assertThat(rejected.seatIds()).containsExactly(SEAT_2);
        // Nothing was written: no seat key for the free seats, no hold, no allowance, no stream entry.
        assertThat(sortedKeys()).isEqualTo(keysBefore);
        assertThat(store.holderOf(EVENT_A, SEAT_1)).isEmpty();
        assertThat(store.holderOf(EVENT_A, SEAT_3)).isEmpty();
        assertThat(store.holderOf(EVENT_A, SEAT_2)).contains(first);
        assertThat(store.used(EVENT_A, USER)).isZero();
        assertThat(streamEntries()).hasSize((int) entriesBefore);
    }

    @Test
    void seatTakenListsEveryTakenSeatInRequestOrder() {
        store.hold(request(UUID.randomUUID(), OTHER_USER, SEAT_1));
        store.hold(request(UUID.randomUUID(), OTHER_USER, SEAT_3));

        Rejected rejected = (Rejected) store.hold(request(UUID.randomUUID(), USER, SEAT_3, SEAT_2, SEAT_1));

        assertThat(rejected.reason()).isEqualTo(HoldRejection.SEAT_TAKEN);
        assertThat(rejected.seatIds()).containsExactly(SEAT_3, SEAT_1);
    }

    @Test
    void takenAgoMsIsTheMostRecentlyTakenSeat() throws InterruptedException {
        store.hold(request(UUID.randomUUID(), OTHER_USER, SEAT_1));
        Thread.sleep(300);
        store.hold(request(UUID.randomUUID(), OTHER_USER, SEAT_2));
        Thread.sleep(50);

        Rejected both = (Rejected) store.hold(request(UUID.randomUUID(), USER, SEAT_1, SEAT_2));
        Rejected older = (Rejected) store.hold(request(UUID.randomUUID(), USER, SEAT_1));

        // SEAT_2 was taken about 50 ms ago, SEAT_1 about 350 ms ago.
        assertThat(both.takenAgoMs()).isBetween(50L, 299L);
        assertThat(older.takenAgoMs()).isGreaterThanOrEqualTo(350L);
    }

    @Test
    void unknownSeatIsRejectedAndNothingChanges() {
        UUID noSuchSeat = UUID.randomUUID();
        List<String> keysBefore = sortedKeys();

        Rejected rejected = (Rejected) store.hold(request(UUID.randomUUID(), USER, SEAT_1, noSuchSeat));

        assertThat(rejected.reason()).isEqualTo(HoldRejection.UNKNOWN_SEAT);
        assertThat(rejected.seatIds()).containsExactly(noSuchSeat);
        assertThat(rejected.takenAgoMs()).isNull();
        assertThat(sortedKeys()).isEqualTo(keysBefore);
    }

    @Test
    void seatOfAnotherEventIsUnknown() {
        Rejected rejected = (Rejected) store.hold(request(UUID.randomUUID(), USER, SEAT_1, SEAT_B));

        assertThat(rejected.reason()).isEqualTo(HoldRejection.UNKNOWN_SEAT);
        assertThat(rejected.seatIds()).containsExactly(SEAT_B);
    }

    @Test
    void unknownSeatWinsOverATakenSeat() {
        store.hold(request(UUID.randomUUID(), OTHER_USER, SEAT_1));
        UUID noSuchSeat = UUID.randomUUID();

        Rejected rejected = (Rejected) store.hold(request(UUID.randomUUID(), USER, SEAT_1, noSuchSeat));

        assertThat(rejected.reason()).isEqualTo(HoldRejection.UNKNOWN_SEAT);
        assertThat(rejected.seatIds()).containsExactly(noSuchSeat);
    }

    @Test
    void holdOfAnEventThatWasNotLoadedThrowsAndWritesNothing() {
        UUID notLoaded = UUID.randomUUID();
        List<String> keysBefore = sortedKeys();

        assertThatThrownBy(() -> store.hold(new HoldRequest(UUID.randomUUID(), notLoaded, USER, List.of(SEAT_1),
                FIVE_MINUTES)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not loaded");

        assertThat(sortedKeys()).isEqualTo(keysBefore);
    }

    @Test
    void reusingALiveHoldIdThrowsAndWritesNothing() {
        UUID holdId = UUID.randomUUID();
        store.hold(request(holdId, USER, SEAT_1));
        List<String> keysBefore = sortedKeys();

        assertThatThrownBy(() -> store.hold(request(holdId, USER, SEAT_2)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already live");

        assertThat(sortedKeys()).isEqualTo(keysBefore);
        assertThat(store.liveHold(EVENT_A, holdId).orElseThrow().seatIds()).containsExactly(SEAT_1);
        assertThat(store.used(EVENT_A, USER)).isEqualTo(1);
    }

    // ---- per-user cap ----

    @Test
    void holdAddsItsSeatsToTheUsersAllowance() {
        store.hold(request(UUID.randomUUID(), USER, SEAT_1, SEAT_2));
        assertThat(store.used(EVENT_A, USER)).isEqualTo(2);

        store.hold(request(UUID.randomUUID(), USER, SEAT_3));
        assertThat(store.used(EVENT_A, USER)).isEqualTo(3);
        assertThat(store.used(EVENT_A, OTHER_USER)).isZero();
    }

    @Test
    void holdUpToExactlyTheLimitSucceeds() {
        assertThat(store.hold(request(UUID.randomUUID(), USER, SEAT_1, SEAT_2, SEAT_3))).isInstanceOf(Held.class);
        assertThat(store.hold(request(UUID.randomUUID(), USER, SEAT_4))).isInstanceOf(Held.class);

        assertThat(store.used(EVENT_A, USER)).isEqualTo(LIMIT);
    }

    @Test
    void holdOverTheLimitIsRejectedAndWritesNothing() {
        store.hold(request(UUID.randomUUID(), USER, SEAT_1, SEAT_2, SEAT_3));
        List<String> keysBefore = sortedKeys();
        int entriesBefore = streamEntries().size();

        Rejected rejected = (Rejected) store.hold(request(UUID.randomUUID(), USER, SEAT_4, SEAT_5));

        assertThat(rejected.reason()).isEqualTo(HoldRejection.LIMIT_EXCEEDED);
        assertThat(rejected.seatIds()).containsExactly(SEAT_4, SEAT_5);
        assertThat(rejected.takenAgoMs()).isNull();
        assertThat(sortedKeys()).isEqualTo(keysBefore);
        assertThat(store.holderOf(EVENT_A, SEAT_4)).isEmpty();
        assertThat(store.holderOf(EVENT_A, SEAT_5)).isEmpty();
        assertThat(store.used(EVENT_A, USER)).isEqualTo(3);
        assertThat(streamEntries()).hasSize(entriesBefore);
    }

    @Test
    void firstHoldLargerThanTheLimitIsRejectedAndWritesNothing() {
        List<String> keysBefore = sortedKeys();

        Rejected rejected = (Rejected) store.hold(request(UUID.randomUUID(), USER, SEAT_1, SEAT_2, SEAT_3, SEAT_4,
                SEAT_5));

        assertThat(rejected.reason()).isEqualTo(HoldRejection.LIMIT_EXCEEDED);
        assertThat(sortedKeys()).isEqualTo(keysBefore);
    }

    @Test
    void theCapIsPerUserAndPerEvent() {
        store.hold(request(UUID.randomUUID(), USER, SEAT_1, SEAT_2, SEAT_3, SEAT_4));

        // Another user is not affected by USER being at the limit.
        assertThat(store.hold(request(UUID.randomUUID(), OTHER_USER, SEAT_5))).isInstanceOf(Held.class);
        // Nor is USER in another event.
        assertThat(store.hold(new HoldRequest(UUID.randomUUID(), EVENT_B, USER, List.of(SEAT_B), FIVE_MINUTES)))
                .isInstanceOf(Held.class);
        assertThat(store.used(EVENT_A, USER)).isEqualTo(4);
        assertThat(store.used(EVENT_B, USER)).isEqualTo(1);
    }

    @Test
    void seatTakenWinsOverLimitExceeded() {
        store.hold(request(UUID.randomUUID(), USER, SEAT_1, SEAT_2, SEAT_3, SEAT_4));
        store.hold(request(UUID.randomUUID(), OTHER_USER, SEAT_5));

        Rejected rejected = (Rejected) store.hold(request(UUID.randomUUID(), USER, SEAT_5));

        assertThat(rejected.reason()).isEqualTo(HoldRejection.SEAT_TAKEN);
        assertThat(rejected.seatIds()).containsExactly(SEAT_5);
    }

    // ---- release ----

    @Test
    void releaseFreesTheSeatsAndGivesThemBackToTheAllowance() {
        UUID holdId = UUID.randomUUID();
        store.hold(request(holdId, USER, SEAT_1, SEAT_2));
        UUID kept = UUID.randomUUID();
        store.hold(request(kept, USER, SEAT_3));

        Release outcome = store.release(EVENT_A, holdId, ReleaseReason.RELEASED);

        assertThat(((Release.Released) outcome).seatIds()).containsExactly(SEAT_1, SEAT_2);
        assertThat(store.holderOf(EVENT_A, SEAT_1)).isEmpty();
        assertThat(store.holderOf(EVENT_A, SEAT_2)).isEmpty();
        assertThat(store.liveHold(EVENT_A, holdId)).isEmpty();
        assertThat(store.used(EVENT_A, USER)).isEqualTo(1);
        assertThat(store.holderOf(EVENT_A, SEAT_3)).contains(kept);
        // The seats can be held again, by anyone.
        assertThat(store.hold(request(UUID.randomUUID(), OTHER_USER, SEAT_1, SEAT_2))).isInstanceOf(Held.class);
    }

    @Test
    void sagaFailureReleasesLikeACancel() {
        UUID holdId = UUID.randomUUID();
        store.hold(request(holdId, USER, SEAT_1));

        Release outcome = store.release(EVENT_A, holdId, ReleaseReason.SAGA_FAILED);

        assertThat(outcome).isInstanceOf(Release.Released.class);
        assertThat(store.holderOf(EVENT_A, SEAT_1)).isEmpty();
        assertThat(store.used(EVENT_A, USER)).isZero();
    }

    @Test
    void aSecondReleaseFindsTheHoldGoneAndChangesNothing() {
        UUID holdId = UUID.randomUUID();
        store.hold(request(holdId, USER, SEAT_1));
        store.release(EVENT_A, holdId, ReleaseReason.RELEASED);
        UUID next = UUID.randomUUID();
        store.hold(request(next, OTHER_USER, SEAT_1));
        int entriesBefore = streamEntries().size();

        Release outcome = store.release(EVENT_A, holdId, ReleaseReason.RELEASED);

        assertThat(outcome).isInstanceOf(Release.Gone.class);
        assertThat(store.holderOf(EVENT_A, SEAT_1)).contains(next);
        assertThat(store.used(EVENT_A, OTHER_USER)).isEqualTo(1);
        assertThat(streamEntries()).hasSize(entriesBefore);
    }

    @Test
    void releaseOfAnUnknownHoldOrAHoldOfAnotherEventIsGone() {
        UUID holdId = UUID.randomUUID();
        store.hold(request(holdId, USER, SEAT_1));

        assertThat(store.release(EVENT_A, UUID.randomUUID(), ReleaseReason.RELEASED)).isInstanceOf(Release.Gone.class);
        assertThat(store.release(EVENT_B, holdId, ReleaseReason.RELEASED)).isInstanceOf(Release.Gone.class);
        assertThat(store.holderOf(EVENT_A, SEAT_1)).contains(holdId);
    }

    @Test
    void expiryOfAHoldThatHasNotExpiredChangesNothing() {
        UUID holdId = UUID.randomUUID();
        Held held = (Held) store.hold(request(holdId, USER, SEAT_1));
        int entriesBefore = streamEntries().size();

        Release outcome = store.release(EVENT_A, holdId, ReleaseReason.EXPIRED);

        assertThat(((Release.NotExpired) outcome).expiresAt()).isEqualTo(held.expiresAt());
        assertThat(store.holderOf(EVENT_A, SEAT_1)).contains(holdId);
        assertThat(store.used(EVENT_A, USER)).isEqualTo(1);
        assertThat(streamEntries()).hasSize(entriesBefore);
    }

    @Test
    void releaseNeverDeletesASeatKeyThatNamesAnotherOwner() {
        UUID holdId = UUID.randomUUID();
        store.hold(request(holdId, USER, SEAT_1, SEAT_2));
        // Drift made by hand: SEAT_2's key now names another hold (the scripts can't produce this).
        UUID intruder = UUID.randomUUID();
        redis.opsForValue().set(store.keys().seat(EVENT_A, SEAT_2), "H:" + intruder + ":1");

        store.release(EVENT_A, holdId, ReleaseReason.RELEASED);

        assertThat(store.holderOf(EVENT_A, SEAT_1)).isEmpty();
        assertThat(store.holderOf(EVENT_A, SEAT_2)).contains(intruder);
    }

    // ---- expiry by TTL ----

    @Test
    void whenTheTtlRunsOutSeatsHoldAndAllowanceAllVanishAndTheSeatsCanBeHeldAgain() {
        UUID holdId = UUID.randomUUID();
        store.hold(new HoldRequest(holdId, EVENT_A, USER, List.of(SEAT_1, SEAT_2, SEAT_3, SEAT_4),
                Duration.ofSeconds(1)));
        assertThat(store.used(EVENT_A, USER)).isEqualTo(LIMIT);
        int entriesBefore = streamEntries().size();

        await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(20))
                .until(() -> store.liveHold(EVENT_A, holdId).isEmpty());

        // No sweeper and no script ran: Redis deleted the keys itself, all at the same instant.
        for (UUID seat : List.of(SEAT_1, SEAT_2, SEAT_3, SEAT_4)) {
            assertThat(store.holderOf(EVENT_A, seat)).as("seat %s", seat).isEmpty();
        }
        assertThat(store.used(EVENT_A, USER)).isZero();
        assertThat(store.release(EVENT_A, holdId, ReleaseReason.EXPIRED)).isInstanceOf(Release.Gone.class);
        assertThat(streamEntries()).as("expiry writes no stream entry").hasSize(entriesBefore);
        // The user was at the limit; the expired hold no longer counts, so they can hold again.
        assertThat(store.hold(request(UUID.randomUUID(), USER, SEAT_1, SEAT_2, SEAT_3, SEAT_4))).isInstanceOf(Held.class);
    }

    @Test
    void anExpiredHoldStopsCountingWhileALongerHoldOfTheSameUserStillCounts() {
        UUID shortHold = UUID.randomUUID();
        store.hold(new HoldRequest(shortHold, EVENT_A, USER, List.of(SEAT_1, SEAT_2), Duration.ofSeconds(1)));
        UUID longHold = UUID.randomUUID();
        store.hold(request(longHold, USER, SEAT_3, SEAT_4));

        await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(20))
                .until(() -> store.liveHold(EVENT_A, shortHold).isEmpty());

        assertThat(store.used(EVENT_A, USER)).isEqualTo(2);
        assertThat(store.holderOf(EVENT_A, SEAT_3)).contains(longHold);
        // Two seats are free under the cap again, a third would exceed it.
        Rejected over = (Rejected) store.hold(request(UUID.randomUUID(), USER, SEAT_1, SEAT_2, SEAT_5));
        assertThat(over.reason()).isEqualTo(HoldRejection.LIMIT_EXCEEDED);
        assertThat(store.hold(request(UUID.randomUUID(), USER, SEAT_1, SEAT_2))).isInstanceOf(Held.class);
    }

    // ---- stream ----

    @Test
    void everyHoldAndReleaseAppendsOneStreamEntryAndRejectionsAppendNone() {
        UUID holdId = UUID.randomUUID();
        Held held = (Held) store.hold(request(holdId, USER, SEAT_1, SEAT_2));
        store.hold(request(UUID.randomUUID(), OTHER_USER, SEAT_2));                    // SEAT_TAKEN
        store.hold(request(UUID.randomUUID(), OTHER_USER, UUID.randomUUID()));        // UNKNOWN_SEAT
        store.release(EVENT_A, holdId, ReleaseReason.SAGA_FAILED);

        List<Map<Object, Object>> entries = streamEntries();

        assertThat(entries).hasSize(2);
        long expiresMs = held.expiresAt().toEpochMilli();
        assertThat(entries.get(0)).containsOnly(
                Map.entry("type", "HELD"),
                Map.entry("event", EVENT_A.toString()),
                Map.entry("hold", holdId.toString()),
                Map.entry("user", USER.toString()),
                Map.entry("seats", SEAT_1 + "," + SEAT_2),
                Map.entry("createdMs", Long.toString(expiresMs - FIVE_MINUTES.toMillis())),
                Map.entry("expiresMs", Long.toString(expiresMs)));
        assertThat(entries.get(1)).containsOnly(
                Map.entry("type", "RELEASED"),
                Map.entry("event", EVENT_A.toString()),
                Map.entry("hold", holdId.toString()),
                Map.entry("user", USER.toString()),
                Map.entry("seats", SEAT_1 + "," + SEAT_2),
                Map.entry("reason", "SAGA_FAILED"));
    }

    // ---- helpers ----

    private static HoldRequest request(UUID holdId, UUID userId, UUID... seats) {
        return new HoldRequest(holdId, EVENT_A, userId, List.of(seats), FIVE_MINUTES);
    }

    private List<String> sortedKeys() {
        return TestRedis.keys(redis, PREFIX).stream().sorted().toList();
    }

    private List<Map<Object, Object>> streamEntries() {
        List<MapRecord<String, Object, Object>> records = redis.opsForStream().range(store.keys().stream(),
                Range.unbounded());
        return records.stream().map(MapRecord::getValue).toList();
    }

    private long redisTimeMillis() {
        return redis.execute((RedisCallback<Long>) connection -> connection.serverCommands().time());
    }

    /** The absolute expiry of a key in milliseconds (PEXPIRETIME), -1 without one. */
    private long expireTimeMillis(String key) {
        return redis.execute(new DefaultRedisScript<>("return redis.call('PEXPIRETIME', KEYS[1])", Long.class),
                List.of(key));
    }
}
