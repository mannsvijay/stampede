package com.stampede.testsupport;

import org.springframework.boot.autoconfigure.data.redis.RedisConnectionDetails;
import org.springframework.boot.autoconfigure.jdbc.JdbcConnectionDetails;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;

/**
 * Connections to the shared test Postgres and Redis ({@link TestDatabases}) for full application
 * contexts. Every test class that imports this (through {@link ApiTestSupport}) and has the same
 * configuration shares one cached context. Each context that does start gets a new, empty database
 * when this bean is created, exactly as it used to get its own Postgres container, and its own Redis
 * key prefix (D-022), so contexts sharing the one Redis never see each other's inventory keys.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    JdbcConnectionDetails postgres() {
        String url = TestDatabases.jdbcUrl(TestDatabases.createForAppContext());
        return new JdbcConnectionDetails() {
            @Override
            public String getUsername() {
                return TestDatabases.username();
            }

            @Override
            public String getPassword() {
                return TestDatabases.password();
            }

            @Override
            public String getJdbcUrl() {
                return url;
            }
        };
    }

    @Bean
    RedisConnectionDetails redis() {
        return new RedisConnectionDetails() {
            @Override
            public Standalone getStandalone() {
                return Standalone.of(TestDatabases.redisHost(), TestDatabases.redisPort());
            }
        };
    }

    /**
     * A {@code DynamicPropertyRegistrar} bean adds properties to the context's Environment before
     * other beans are created, so {@code RedisInventoryProperties} binds this context's prefix.
     */
    @Bean
    DynamicPropertyRegistrar redisKeyPrefix() {
        String prefix = TestDatabases.newAppKeyPrefix();
        return registry -> registry.add("stampede.inventory.redis.key-prefix", () -> prefix);
    }
}
