package com.stampede.control.admin;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stampede.inventory.api.EventStrategy;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Writes {@code audit_log} rows (control owns the table, D-007; append-only, V1). Not transactional
 * itself: a row joins whatever transaction the caller runs it in, so it commits or rolls back with
 * the change it records.
 */
@Component
class AuditLog {

    /** {@code audit_log.action} of a strategy switch. */
    static final String STRATEGY_CHANGED = "event.strategy_changed";

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    AuditLog(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /**
     * One row for an admin's strategy switch: actor = the admin's user id, entity = {@code event:<id>},
     * before and after = {@code {"strategy": ...}}.
     */
    void strategyChanged(UUID adminId, UUID eventId, EventStrategy from, EventStrategy to) {
        jdbc.sql("""
                        INSERT INTO audit_log (actor, action, entity, before, after)
                        VALUES (:actor, :action, :entity, CAST(:before AS jsonb), CAST(:after AS jsonb))
                        """)
                .param("actor", adminId.toString())
                .param("action", STRATEGY_CHANGED)
                .param("entity", "event:" + eventId)
                .param("before", toJson(Map.of("strategy", from.name())))
                .param("after", toJson(Map.of("strategy", to.name())))
                .update();
    }

    private String toJson(Map<String, ?> value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not write an audit value as JSON", e);
        }
    }
}
