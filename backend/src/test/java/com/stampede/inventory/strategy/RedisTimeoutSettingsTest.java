package com.stampede.inventory.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;

/**
 * Pins the Redis command timeouts: 2 s in production ({@code application.yml}), 10 s in tests
 * ({@code config/application.properties}), and the consumer's blocking read below both.
 *
 * <p>Why tests differ: long 1000-round runs on an overloaded laptop hit 2 s client-side stalls while Redis
 * itself was idle (SLOWLOG empty). Correctness checks don't depend on the timeout, and how long Redis
 * blocks is measured server side by {@code RedisHoldStateReaderTest}. This test makes sure the larger
 * value never leaks into production and the production value is never raised by accident.
 *
 * <p>Reads the two files directly (no Spring context), so it checks what each file says, not what a
 * developer's {@code .env} might override.
 */
class RedisTimeoutSettingsTest {

    @Test
    void productionKeepsTheTwoSecondTimeoutAndABlockBelowIt() throws Exception {
        Properties production = productionYaml();

        Duration timeout = duration(production, "spring.data.redis.timeout");
        Duration block = duration(production, "stampede.inventory.redis.consumer.block");

        assertThat(timeout).isEqualTo(Duration.ofSeconds(2));
        assertThat(block).isLessThan(timeout);
    }

    @Test
    void testsUseTenSecondsAndStayAboveTheProductionValue() throws Exception {
        Properties tests = PropertiesLoaderUtils.loadProperties(new ClassPathResource("config/application.properties"));

        Duration testTimeout = duration(tests, "spring.data.redis.timeout");

        assertThat(testTimeout).isEqualTo(Duration.ofSeconds(10));
        assertThat(testTimeout).isGreaterThan(duration(productionYaml(), "spring.data.redis.timeout"));
    }

    @Test
    void theBlockGuardStillRejectsAnythingAtOrNearTheProductionTimeout() {
        // The guard is hardcoded to 1.9 s (production's 2 s minus a margin), not derived from the timeout
        // property, so the 10 s test timeout can't loosen it.
        assertThat(consumerWithBlock(Duration.ofMillis(1900)).block()).isEqualTo(Duration.ofMillis(1900));
        assertThatThrownBy(() -> consumerWithBlock(Duration.ofMillis(1901)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> consumerWithBlock(Duration.ofSeconds(2)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static Properties productionYaml() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        return yaml.getObject();
    }

    private static Duration duration(Properties properties, String key) {
        String value = properties.getProperty(key);
        assertThat(value).as(key).isNotNull();
        return DurationStyle.detectAndParse(value);
    }

    private static RedisInventoryProperties.Consumer consumerWithBlock(Duration block) {
        return new RedisInventoryProperties.Consumer(true, "", block, 100, Duration.ofSeconds(30), 5);
    }
}
