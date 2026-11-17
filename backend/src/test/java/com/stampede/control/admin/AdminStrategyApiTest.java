package com.stampede.control.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.stampede.testsupport.ApiTestSupport;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;

/**
 * POST /admin/events/{id}/strategy over HTTP (M2.3a, D-023): who may call it, what it answers, and the
 * audit row it writes in the same transaction as the switch. The switch rules themselves are covered
 * against Postgres and Redis by {@code StrategySwitcherTest}.
 */
class AdminStrategyApiTest extends ApiTestSupport {

    static final UUID VENUE = UUID.fromString("00000000-0000-4000-8000-0000000002a1");
    static final UUID SECTION = UUID.fromString("00000000-0000-4000-8000-0000000002a2");
    static final UUID EVENT = UUID.fromString("00000000-0000-4000-8000-0000000002a3");
    static final UUID TIER = UUID.fromString("00000000-0000-4000-8000-0000000002a4");
    static final UUID SEAT = UUID.fromString("10000000-0000-4000-8000-0000000002b1");

    private String admin;
    private UUID adminId;
    private String user;

    @BeforeEach
    void insertEventAndLogIn() {
        jdbc.sql("INSERT INTO venues (id, name, layout_json) VALUES (?, 'Admin venue', '{}')").param(VENUE).update();
        jdbc.sql("INSERT INTO sections (id, venue_id, code, default_tier) VALUES (?, ?, 'A', 'GOLD')")
                .params(SECTION, VENUE).update();
        // Its drop is tomorrow: not on sale, so a switch is allowed (D-023).
        jdbc.sql("""
                INSERT INTO events (id, venue_id, title, starts_at, drop_at, status, per_user_limit, strategy)
                VALUES (?, ?, 'Admin test event', now() + interval '30 days', now() + interval '1 day',
                        'SCHEDULED', 4, 'PESSIMISTIC')
                """).params(EVENT, VENUE).update();
        jdbc.sql("INSERT INTO price_tiers (id, event_id, name, price_paise) VALUES (?, ?, 'GOLD', 150000)")
                .params(TIER, EVENT).update();
        jdbc.sql("INSERT INTO seats (id, section_id, row_label, number, x, y) VALUES (?, ?, 'A', 1, 0, 0)")
                .params(SEAT, SECTION).update();
        jdbc.sql("INSERT INTO event_seats (event_id, seat_id, tier_id, status) VALUES (?, ?, ?, 'AVAILABLE')")
                .params(EVENT, SEAT, TIER).update();

        String adminEmail = uniqueEmail();
        registerAndLogin(adminEmail);
        jdbc.sql("UPDATE users SET role = 'ADMIN' WHERE email = ?").param(adminEmail).update();
        admin = login(adminEmail, PASSWORD).accessToken();   // a new token carries the ADMIN role
        adminId = jdbc.sql("SELECT id FROM users WHERE email = ?").param(adminEmail).query(UUID.class).single();
        user = registerAndLogin(uniqueEmail()).accessToken();
    }

    /** Other tests share this database. audit_log rows stay: the table is append-only (V1). */
    @AfterEach
    void deleteEvent() {
        jdbc.sql("DROP TRIGGER IF EXISTS test_fail_audit_insert ON audit_log").update();
        jdbc.sql("DELETE FROM event_seats WHERE event_id = ?").param(EVENT).update();
        jdbc.sql("DELETE FROM holds WHERE event_id = ?").param(EVENT).update();
        jdbc.sql("DELETE FROM user_event_allowance WHERE event_id = ?").param(EVENT).update();
        jdbc.sql("DELETE FROM price_tiers WHERE event_id = ?").param(EVENT).update();
        jdbc.sql("DELETE FROM events WHERE id = ?").param(EVENT).update();
        jdbc.sql("DELETE FROM seats WHERE section_id = ?").param(SECTION).update();
        jdbc.sql("DELETE FROM sections WHERE id = ?").param(SECTION).update();
        jdbc.sql("DELETE FROM venues WHERE id = ?").param(VENUE).update();
    }

    // --- who may call it ---------------------------------------------------------------------------

    @Test
    void withoutATokenIs401() {
        Response response = postStrategy(EVENT, null, Map.of("strategy", "OPTIMISTIC"));

        assertThat(response.status()).isEqualTo(401);
        assertThat(response.code()).isEqualTo("UNAUTHENTICATED");
        assertThat(strategy()).isEqualTo("PESSIMISTIC");
    }

    @Test
    void aUserWhoIsNotAnAdminGets403() {
        Response response = postStrategy(EVENT, user, Map.of("strategy", "OPTIMISTIC"));

        assertThat(response.status()).isEqualTo(403);
        assertThat(response.code()).isEqualTo("FORBIDDEN");
        assertThat(strategy()).isEqualTo("PESSIMISTIC");
        assertThat(auditRows()).isEmpty();
    }

    // --- 200 ---------------------------------------------------------------------------------------

    @Test
    void anAdminSwitchesTheStrategyAndOneAuditRowIsWritten() throws Exception {
        Response response = postStrategy(EVENT, admin, Map.of("strategy", "OPTIMISTIC"));

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body().path("eventId").asText()).isEqualTo(EVENT.toString());
        assertThat(response.body().path("strategy").asText()).isEqualTo("OPTIMISTIC");
        assertThat(response.body().path("previous").asText()).isEqualTo("PESSIMISTIC");
        assertThat(response.body().path("changed").asBoolean()).isTrue();
        assertThat(strategy()).isEqualTo("OPTIMISTIC");

        List<Map<String, Object>> rows = auditRows();
        assertThat(rows).hasSize(1);
        Map<String, Object> row = rows.get(0);
        assertThat(row.get("actor")).isEqualTo(adminId.toString());
        assertThat(row.get("action")).isEqualTo("event.strategy_changed");
        assertThat(row.get("entity")).isEqualTo("event:" + EVENT);
        assertThat(json.readTree(row.get("before").toString())).isEqualTo(json.readTree("{\"strategy\":\"PESSIMISTIC\"}"));
        assertThat(json.readTree(row.get("after").toString())).isEqualTo(json.readTree("{\"strategy\":\"OPTIMISTIC\"}"));
        assertThat(row.get("at")).isNotNull();
    }

    @Test
    void theStrategyTheEventAlreadyHasIs200UnchangedWithNoAuditRow() {
        Response response = postStrategy(EVENT, admin, Map.of("strategy", "PESSIMISTIC"));

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body().path("strategy").asText()).isEqualTo("PESSIMISTIC");
        assertThat(response.body().path("previous").asText()).isEqualTo("PESSIMISTIC");
        assertThat(response.body().path("changed").asBoolean()).isFalse();
        assertThat(auditRows()).isEmpty();
    }

    // --- 400, 404, 409 -----------------------------------------------------------------------------

    @Test
    void aMissingOrUnknownStrategyIs400() {
        Response missing = postStrategy(EVENT, admin, Map.of());
        Response unknown = postStrategy(EVENT, admin, Map.of("strategy", "FASTEST"));
        Response badId = postStrategy("not-a-uuid", admin, Map.of("strategy", "OPTIMISTIC"));

        for (Response response : List.of(missing, unknown, badId)) {
            assertThat(response.status()).isEqualTo(400);
            assertThat(response.code()).isEqualTo("VALIDATION_FAILED");
        }
        assertThat(missing.body().path("errors").get(0).path("field").asText()).isEqualTo("strategy");
        assertThat(strategy()).isEqualTo("PESSIMISTIC");
        assertThat(auditRows()).isEmpty();
    }

    @Test
    void anUnknownEventIs404() {
        Response response = postStrategy(UUID.randomUUID(), admin, Map.of("strategy", "OPTIMISTIC"));

        assertThat(response.status()).isEqualTo(404);
        assertThat(response.code()).isEqualTo("EVENT_NOT_FOUND");
        assertThat(auditRows()).isEmpty();
    }

    @Test
    void anEventOnSaleIs409WithTheReasonAndNoAuditRow() {
        jdbc.sql("UPDATE events SET drop_at = now() - interval '1 hour' WHERE id = ?").param(EVENT).update();

        Response response = postStrategy(EVENT, admin, Map.of("strategy", "OPTIMISTIC"));

        assertThat(response.status()).isEqualTo(409);
        assertThat(response.code()).isEqualTo("STRATEGY_SWITCH_REFUSED");
        assertThat(response.body().path("reason").asText()).isEqualTo("ON_SALE");
        assertThat(response.body().path("strategy").asText()).isEqualTo("PESSIMISTIC");
        assertThat(strategy()).isEqualTo("PESSIMISTIC");
        assertThat(auditRows()).isEmpty();
    }

    @Test
    void anActiveHoldIs409LiveHolds() {
        UUID holdId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO holds (id, event_id, user_id, seat_ids, expires_at, status)
                VALUES (?, ?, ?, ARRAY[?]::uuid[], now() + interval '5 minutes', 'ACTIVE')
                """).params(holdId, EVENT, adminId, SEAT).update();
        jdbc.sql("UPDATE event_seats SET status = 'HELD', hold_id = ?, version = 1 WHERE event_id = ? AND seat_id = ?")
                .params(holdId, EVENT, SEAT).update();
        jdbc.sql("INSERT INTO user_event_allowance (event_id, user_id, used) VALUES (?, ?, 1)")
                .params(EVENT, adminId).update();

        Response response = postStrategy(EVENT, admin, Map.of("strategy", "OPTIMISTIC"));

        assertThat(response.status()).isEqualTo(409);
        assertThat(response.code()).isEqualTo("STRATEGY_SWITCH_REFUSED");
        assertThat(response.body().path("reason").asText()).isEqualTo("LIVE_HOLDS");
        assertThat(strategy()).isEqualTo("PESSIMISTIC");
        assertThat(auditRows()).isEmpty();
    }

    /** The audit row is part of the switch's transaction: when it can't be written, nothing is switched. */
    @Test
    void whenTheAuditRowFailsTheSwitchRollsBack() {
        jdbc.sql("""
                CREATE OR REPLACE FUNCTION test_fail_audit_insert() RETURNS trigger AS $$
                BEGIN
                    RAISE EXCEPTION 'test: audit inserts are failing';
                END
                $$ LANGUAGE plpgsql
                """).update();
        jdbc.sql("""
                CREATE TRIGGER test_fail_audit_insert BEFORE INSERT ON audit_log
                FOR EACH ROW EXECUTE FUNCTION test_fail_audit_insert()
                """).update();

        Response response = postStrategy(EVENT, admin, Map.of("strategy", "OPTIMISTIC"));

        assertThat(response.status()).isEqualTo(500);
        assertThat(response.code()).isEqualTo("INTERNAL_ERROR");
        assertThat(strategy()).as("rolled back with the audit row").isEqualTo("PESSIMISTIC");
    }

    // --- helpers -----------------------------------------------------------------------------------

    private Response postStrategy(Object eventId, String token, Map<String, ?> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return exchange(HttpMethod.POST, "/admin/events/" + eventId + "/strategy", new HttpEntity<>(body, headers));
    }

    private String strategy() {
        return jdbc.sql("SELECT strategy FROM events WHERE id = ?").param(EVENT).query(String.class).single();
    }

    /** This test's audit rows: its admin is a new user, so no other test wrote rows with this actor. */
    private List<Map<String, Object>> auditRows() {
        return jdbc.sql("""
                        SELECT actor, action, entity, before::text AS before, after::text AS after, at
                        FROM audit_log WHERE actor = ? ORDER BY at
                        """)
                .param(adminId.toString())
                .query()
                .listOfRows();
    }
}
