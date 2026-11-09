package com.stampede.inventory.strategy;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Hold-level invariants as SQL (M1.3). Each query returns the rows that BREAK the rule, so an empty
 * result means the rule holds. Written to be taken over by the invariant checker in M1.7.
 */
final class HoldInvariants {

    /** I1: a seat is listed by at most one ACTIVE hold. */
    static final String SEAT_IN_TWO_ACTIVE_HOLDS = """
            SELECT s.seat_id::text || ' listed by active holds ' || array_agg(h.id)::text
            FROM holds h
            CROSS JOIN LATERAL unnest(h.seat_ids) AS s(seat_id)
            WHERE h.event_id = ? AND h.status = 'ACTIVE'
            GROUP BY s.seat_id
            HAVING count(*) > 1
            """;

    /** I2: every HELD seat points to a hold that exists, is ACTIVE and lists that seat. */
    static final String HELD_SEAT_WITHOUT_ITS_ACTIVE_HOLD = """
            SELECT es.seat_id::text || ' is HELD by ' || es.hold_id::text || ' whose status is '
                   || coalesce(h.status, 'MISSING')
                   || CASE WHEN h.id IS NOT NULL AND NOT es.seat_id = ANY (h.seat_ids)
                           THEN ' and which does not list it' ELSE '' END
            FROM event_seats es
            LEFT JOIN holds h ON h.id = es.hold_id
            WHERE es.event_id = ? AND es.status = 'HELD'
              AND (h.id IS NULL OR h.status <> 'ACTIVE' OR NOT es.seat_id = ANY (h.seat_ids))
            """;

    /** I3: every seat an ACTIVE hold lists is really HELD by that hold (the other direction is I2). */
    static final String ACTIVE_HOLD_NOT_OWNING_ITS_SEAT = """
            SELECT 'active hold ' || h.id::text || ' lists ' || s.seat_id::text || ' but the seat is '
                   || coalesce(es.status, 'MISSING') || ' owned by ' || coalesce(es.hold_id::text, 'nobody')
            FROM holds h
            CROSS JOIN LATERAL unnest(h.seat_ids) AS s(seat_id)
            LEFT JOIN event_seats es ON es.event_id = h.event_id AND es.seat_id = s.seat_id
            WHERE h.event_id = ? AND h.status = 'ACTIVE'
              AND (es.status IS DISTINCT FROM 'HELD' OR es.hold_id IS DISTINCT FROM h.id)
            """;

    private HoldInvariants() {
    }

    /** Runs I1 to I3 for one event and returns every violation, prefixed with the rule's name. */
    static List<String> check(JdbcClient jdbc, UUID eventId) {
        List<String> violations = new ArrayList<>();
        violations.addAll(run(jdbc, eventId, "I1 seat in two active holds", SEAT_IN_TWO_ACTIVE_HOLDS));
        violations.addAll(run(jdbc, eventId, "I2 held seat without its active hold", HELD_SEAT_WITHOUT_ITS_ACTIVE_HOLD));
        violations.addAll(run(jdbc, eventId, "I3 active hold not owning its seat", ACTIVE_HOLD_NOT_OWNING_ITS_SEAT));
        return violations;
    }

    private static List<String> run(JdbcClient jdbc, UUID eventId, String rule, String sql) {
        return jdbc.sql(sql).param(eventId).query(String.class).list().stream()
                .map(detail -> rule + ": " + detail)
                .toList();
    }
}
