package com.stampede.testsupport;

import java.util.ArrayList;
import java.util.List;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;

/** Helpers for tests that write to the shared test Redis ({@link TestDatabases}). */
public final class TestRedis {

    private TestRedis() {
    }

    /**
     * Deletes every key that starts with {@code prefix + ":"} and returns how many there were. The
     * shared Redis holds other contexts' keys too, so tests clean up by their own prefix and never
     * use FLUSHDB.
     */
    public static int deleteKeys(StringRedisTemplate redis, String prefix) {
        List<String> found = keys(redis, prefix);
        if (!found.isEmpty()) {
            redis.delete(found);
        }
        return found.size();
    }

    /** Every key that starts with {@code prefix + ":"}. */
    public static List<String> keys(StringRedisTemplate redis, String prefix) {
        List<String> found = new ArrayList<>();
        // SCAN walks the keyspace in pages instead of blocking Redis the way KEYS does.
        try (Cursor<String> cursor = redis.scan(ScanOptions.scanOptions().match(prefix + ":*").count(1000).build())) {
            cursor.forEachRemaining(found::add);
        }
        return found;
    }
}
