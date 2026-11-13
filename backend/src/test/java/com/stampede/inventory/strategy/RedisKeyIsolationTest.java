package com.stampede.inventory.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stampede.inventory.strategy.HoldOutcome.Held;
import com.stampede.inventory.strategy.RedisHoldStore.Release;
import com.stampede.testsupport.TestDatabases;
import com.stampede.testsupport.TestRedis;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
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
 * M2.2a (PLAN note on M2.2): every test context shares one Redis server, so two contexts must not see
 * each other's inventory keys. A context's keys all start with its
 * {@code stampede.inventory.redis.key-prefix}. Here the second "context" is a second store built by
 * hand with another prefix, on the same Redis, using the same event, seat and user ids.
 */
@DataRedisTest
@Import({RedisHoldStore.class, InventoryStrategyConfig.class})
class RedisKeyIsolationTest {

    static final String PREFIX = "redis_key_isolation";
    static final String OTHER_PREFIX = "redis_key_isolation_other";

    @DynamicPropertySource
    static void redis(DynamicPropertyRegistry registry) {
        TestDatabases.registerRedis(registry, PREFIX);
    }

    static final UUID USER = UUID.fromString("00000000-0000-4000-8000-0000000000a1");
    static final UUID EVENT = UUID.fromString("00000000-0000-4000-8000-00000000000a");
    static final UUID SEAT_1 = UUID.fromString("10000000-0000-4000-8000-000000000001");
    static final UUID SEAT_2 = UUID.fromString("90000000-0000-4000-8000-000000000002");

    @Autowired
    RedisHoldStore store;

    @Autowired
    StringRedisTemplate redis;

    private RedisHoldStore other;

    @BeforeEach
    void loadTheSameEventUnderBothPrefixes() {
        deleteKeys();
        other = new RedisHoldStore(redis, new RedisInventoryProperties(OTHER_PREFIX));
        store.loadEvent(EVENT, List.of(SEAT_1, SEAT_2), 2);
        other.loadEvent(EVENT, List.of(SEAT_1, SEAT_2), 2);
    }

    @AfterEach
    void deleteKeys() {
        TestRedis.deleteKeys(redis, PREFIX);
        TestRedis.deleteKeys(redis, OTHER_PREFIX);
    }

    @Test
    void theContextsStoreUsesTheContextsPrefix() {
        assertThat(store.keys().all()).isEqualTo(PREFIX + ":");
    }

    @Test
    void aHoldUnderOnePrefixIsInvisibleUnderTheOther() {
        UUID holdId = UUID.randomUUID();
        assertThat(store.hold(request(holdId, SEAT_1, SEAT_2))).isInstanceOf(Held.class);

        assertThat(other.holderOf(EVENT, SEAT_1)).isEmpty();
        assertThat(other.liveHold(EVENT, holdId)).isEmpty();
        assertThat(other.used(EVENT, USER)).isZero();
        assertThat(other.release(EVENT, holdId, ReleaseReason.RELEASED)).isInstanceOf(Release.Gone.class);
        // The same seats, the same user at the same limit: the other prefix is a separate world.
        assertThat(other.hold(request(UUID.randomUUID(), SEAT_1, SEAT_2))).isInstanceOf(Held.class);
        assertThat(store.holderOf(EVENT, SEAT_1)).contains(holdId);
        assertThat(store.used(EVENT, USER)).isEqualTo(2);
    }

    @Test
    void anEventLoadedUnderOnePrefixIsNotLoadedUnderTheOther() {
        UUID onlyHere = UUID.randomUUID();
        store.loadEvent(onlyHere, List.of(SEAT_1), 2);

        assertThatThrownBy(() -> other.hold(new HoldRequest(UUID.randomUUID(), onlyHere, USER, List.of(SEAT_1),
                Duration.ofMinutes(5))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not loaded");
    }

    @Test
    void everyKeyTheStoreWritesStartsWithItsPrefix() {
        // The shared server holds other contexts' keys too, so compare before and after, and only keys
        // that mention this test's event or are an inventory stream.
        List<String> outsideBefore = keysOutsideBothPrefixes();

        UUID holdId = UUID.randomUUID();
        store.hold(request(holdId, SEAT_1));
        store.hold(request(UUID.randomUUID(), SEAT_2));
        store.release(EVENT, holdId, ReleaseReason.RELEASED);

        assertThat(TestRedis.keys(redis, PREFIX)).isNotEmpty().allMatch(key -> key.startsWith(PREFIX + ":"));
        assertThat(keysOutsideBothPrefixes()).isEqualTo(outsideBefore);
    }

    @Test
    void deletingOnePrefixsKeysLeavesTheOtherIntact() {
        UUID mine = UUID.randomUUID();
        UUID theirs = UUID.randomUUID();
        store.hold(request(mine, SEAT_1));
        other.hold(request(theirs, SEAT_1));
        List<String> othersKeys = TestRedis.keys(redis, OTHER_PREFIX).stream().sorted().toList();

        int deleted = TestRedis.deleteKeys(redis, PREFIX);

        // meta, seats, seat key, hold, user, stream
        assertThat(deleted).isEqualTo(6);
        assertThat(TestRedis.keys(redis, PREFIX)).isEmpty();
        assertThat(TestRedis.keys(redis, OTHER_PREFIX).stream().sorted().toList()).isEqualTo(othersKeys);
        assertThat(other.holderOf(EVENT, SEAT_1)).contains(theirs);
    }

    @Test
    void aPrefixThatCouldBreakKeysOrScansIsRefused() {
        for (String bad : List.of("", "Stampede", "a:b", "a*", "1abc", "a b")) {
            assertThatThrownBy(() -> new RedisInventoryProperties(bad)).as("prefix '%s'", bad)
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(new RedisInventoryProperties("app_keys_12").keyPrefix()).isEqualTo("app_keys_12");
    }

    private static HoldRequest request(UUID holdId, UUID... seats) {
        return new HoldRequest(holdId, EVENT, USER, List.of(seats), Duration.ofMinutes(5));
    }

    /** Keys outside this test's two prefixes that the store could have written by mistake. */
    private List<String> keysOutsideBothPrefixes() {
        return redis.keys("*").stream()
                .filter(key -> !key.startsWith(PREFIX + ":") && !key.startsWith(OTHER_PREFIX + ":"))
                .filter(key -> key.contains(EVENT.toString()) || key.endsWith(":inv:stream"))
                .sorted().toList();
    }
}
