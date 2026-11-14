package com.stampede.inventory.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.stampede.testsupport.TestDatabases;
import com.zaxxer.hikari.HikariDataSource;
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
 * M2.2c-2: the concurrency harness ({@link StrategyRaceTest}, all four scenarios) against the
 * Redis-first strategy. Every round starts from an empty Redis prefix with the event freshly loaded,
 * and every check runs after the stream is drained ({@link RedisRoundHooks}), so I4-I7 and the
 * invariant checker, invariant 5 (I10) included, hold strictly: zero tolerance (D-022).
 * <p>
 * Its own Spring context, database and key prefix ({@code redis_first_concurrency}), checked before every
 * test by {@link #ownStores()}.
 */
@ImportAutoConfiguration(RedisAutoConfiguration.class)
@Import({RedisHoldStore.class, RedisStreamMetrics.class, RedisHoldWriter.class, RedisEventLoader.class,
        RedisStreamConsumer.class, RedisCatchUp.class, RedisFirstInventoryStrategy.class, RedisHoldStateReader.class})
// The drain reads with a 10 ms block, so an empty stream doesn't cost a second per round.
@TestPropertySource(properties = {
        "stampede.inventory.redis.consumer.block=10ms",
        "stampede.inventory.redis.consumer.claim-min-idle=0s"})
class RedisFirstConcurrencyTest extends StrategyRaceTest {

    static final String STORES = "redis_first_concurrency";

    /** Its own database and key prefix on the shared test servers; the parent registers none. */
    @DynamicPropertySource
    static void redisFirstStores(DynamicPropertyRegistry registry) {
        TestDatabases.register(registry, STORES);
        TestDatabases.registerRedis(registry, STORES);
    }

    @Autowired
    RedisFirstInventoryStrategy redisFirst;

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
