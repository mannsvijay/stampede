package com.stampede.control.invariants;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * A rule written as one SELECT that returns one text row per violation. The SQL may use the named
 * parameters {@code :eventId} and {@code :graceMs} (0 = strict).
 */
record SqlCheck(String id, String name, String sql) implements InvariantCheck {

    @Override
    public List<String> findViolations(JdbcClient jdbc, UUID eventId, Duration grace) {
        return jdbc.sql(sql)
                .param("eventId", eventId)
                .param("graceMs", grace.toMillis())
                .query(String.class)
                .list();
    }
}
