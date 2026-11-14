package com.stampede.inventory.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.stampede.testsupport.TestDatabases;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/**
 * The per-user cap under concurrency ({@link HoldCapRaceTest}, both scenarios) against the
 * Redis-first strategy, where {@code hold.lua} enforces the cap by recounting the user's live holds
 * (D-022). CLAUDE.md: the cap is enforced under concurrency in every strategy, Lua included. Rounds
 * start from an empty Redis prefix and end with the stream drained ({@link RedisRoundHooks}).
 * <p>
 * Its own Spring context, database and key prefix ({@code redis_first_hold_cap}), checked before every
 * test by {@link #ownStores()}.
 */
@ImportAutoConfiguration(RedisAutoConfiguration.class)
@Import({RedisHoldStore.class, RedisStreamMetrics.class, RedisHoldWriter.class, RedisEventLoader.class,
        RedisStreamConsumer.class, RedisCatchUp.class, RedisFirstInventoryStrategy.class, RedisHoldStateReader.class})
@TestPropertySource(properties = {
        "stampede.inventory.redis.consumer.block=10ms",
        "stampede.inventory.redis.consumer.claim-min-idle=0s"})
class RedisFirstHoldCapConcurrencyTest extends HoldCapRaceTest {

    static final String STORES = "redis_first_hold_cap";

    /** Its own database and key prefix on the shared test servers; the parent registers none. */
    @DynamicPropertySource
    static void redisFirstStores(DynamicPropertyRegistry registry) {
        TestDatabases.register(registry, STORES);
        TestDatabases.registerRedis(registry, STORES);
    }

    @Autowired
    RedisFirstInventoryStrategy redisFirst;

    @Autowired
    DataSource dataSource;

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    RedisHoldStore store;

    @Autowired
    RedisStreamConsumer consumer;

    @Autowired
    RedisEventLoader loader;

    @BeforeEach
    void ownStores() {
        assertThat(((HikariDataSource) dataSource).getJdbcUrl()).as("own database").endsWith("/" + STORES);
        assertThat(store.keys().all()).as("own key prefix").isEqualTo(STORES + ":");
    }

    @Override
    protected InventoryStrategy strategyUnderTest() {
        return redisFirst;
    }

    @Override
    protected ConcurrencyHarness.RoundHooks roundHooks() {
        return new RedisRoundHooks(redis, STORES, consumer, loader, EVENT);
    }
}
