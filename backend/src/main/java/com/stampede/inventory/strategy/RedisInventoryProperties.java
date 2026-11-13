package com.stampede.inventory.strategy;

import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the Redis-first strategy (D-022), {@code stampede.inventory.redis.*}.
 *
 * @param keyPrefix the first part of every inventory key in Redis. Two applications (or two Spring
 *                  test contexts) that share a Redis server must use different prefixes, or they
 *                  would see each other's holds
 */
@ConfigurationProperties("stampede.inventory.redis")
record RedisInventoryProperties(@DefaultValue("stampede") String keyPrefix) {

    // No ':' (the separator inside keys) and no glob characters (cleanup scans by prefix).
    private static final Pattern PREFIX = Pattern.compile("[a-z][a-z0-9_]{0,62}");

    RedisInventoryProperties {
        if (keyPrefix == null || !PREFIX.matcher(keyPrefix).matches()) {
            throw new IllegalArgumentException("stampede.inventory.redis.key-prefix must be lowercase letters, "
                    + "digits and '_' and start with a letter, was '" + keyPrefix + "'");
        }
    }
}
