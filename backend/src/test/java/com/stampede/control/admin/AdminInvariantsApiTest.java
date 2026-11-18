package com.stampede.control.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.stampede.inventory.strategy.RedisFirstTestAccess;
import com.stampede.testsupport.ApiTestSupport;
import com.stampede.testsupport.ExpectInvariantViolations;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;

/**
 * GET /admin/invariants over HTTP (M2.4a, D-024): who may call it, what it answers, the grace it uses,
 * and, for a REDIS_FIRST event, that it reports the stream lag that makes a check inconclusive. The rules
 * themselves are covered by the invariant checker's own tests.
 */
class AdminInvariantsApiTest extends ApiTestSupport {

    static final UUID VENUE = UUID.fromString("00000000-0000-4000-8000-0000000002c1");
    static final UUID SECTION = UUID.fromString("00000000-0000-4000-8000-0000000002c2");
    static final UUID EVENT = UUID.fromString("00000000-0000-4000-8000-0000000002c3");
    static final UUID TIER = UUID.fromString("00000000-0000-4000-8000-0000000002c4");
    static final UUID SEAT = UUID.fromString("10000000-0000-4000-8000-0000000002d1");

    @Autowired
    private ApplicationContext context;

    private String admin;
    private String user;
    private UUID userId;

    @BeforeEach
    void insertEventAndLogIn() {
        jdbc.sql("INSERT INTO venues (id, name, layout_json) VALUES (?, 'Invariants venue', '{}')").param(VENUE).update();
        jdbc.sql("INSERT INTO sections (id, venue_id, code, default_tier) VALUES (?, ?, 'A', 'GOLD')")
                .params(SECTION, VENUE).update();
        // On sale, so the REDIS_FIRST test can hold a seat.
        jdbc.sql("""
                INSERT INTO events (id, venue_id, title, starts_at, drop_at, status, per_user_limit, strategy)
                VALUES (?, ?, 'Invariants test event', now() + interval '30 days', now() - interval '1 hour',
                        'LIVE', 4, 'PESSIMISTIC')
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
        String userEmail = uniqueEmail();
        user = registerAndLogin(userEmail).accessToken();
        userId = jdbc.sql("SELECT id FROM users WHERE email = ?").param(userEmail).query(UUID.class).single();
    }

    /** Other tests share this database. */
    @AfterEach
    void deleteEvent() {
        RedisFirstTestAccess.deleteEventKeys(context, EVENT);
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
        Response response = check("eventId=" + EVENT, null);

        assertThat(response.status()).isEqualTo(401);
        assertThat(response.code()).isEqualTo("UNAUTHENTICATED");
    }

    @Test
    void aUserWhoIsNotAnAdminGets403() {
        Response response = check("eventId=" + EVENT, user);

        assertThat(response.status()).isEqualTo(403);
        assertThat(response.code()).isEqualTo("FORBIDDEN");
    }

    // --- 200 ---------------------------------------------------------------------------------------

    @Test
    void aCleanEventHasNoViolationsAndTheResponseNamesWhatWasChecked() {
        Response response = check("eventId=" + EVENT + "&grace=PT0S", admin);

        assertThat(response.status()).as(String.valueOf(response.body())).isEqualTo(200);
        JsonNode body = response.body();
        assertThat(body.path("eventId").asText()).isEqualTo(EVENT.toString());
        assertThat(body.path("strategy").asText()).isEqualTo("PESSIMISTIC");
        assertThat(body.path("unappliedStreamEntries").asBoolean()).isFalse();
        assertThat(body.path("grace").asText()).isEqualTo("PT0S");
        assertThat(body.path("checkedAt").asText()).isNotBlank();
        assertThat(texts(body.path("rules"))).contains("I1", "I8", "O1", "I10");
        assertThat(body.path("violations").isArray()).isTrue();
        assertThat(body.path("violations")).isEmpty();
    }

    @Test
    void withoutAGraceTheMonitorsGraceApplies() {
        Response response = check("eventId=" + EVENT, admin);

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body().path("grace").asText()).isEqualTo("PT30S");
    }

    @Test
    @ExpectInvariantViolations(rules = {"I8"},
            reason = "Planted on purpose (an allowance row with no holds behind it) to show the endpoint reports a violation")
    void aViolationIsReportedWithItsRuleNameAndDetail() {
        jdbc.sql("INSERT INTO user_event_allowance (event_id, user_id, used) VALUES (?, ?, 3)")
                .params(EVENT, userId).update();

        Response response = check("eventId=" + EVENT + "&grace=PT0S", admin);

        assertThat(response.status()).isEqualTo(200);
        JsonNode violations = response.body().path("violations");
        assertThat(violations).hasSize(1);
        assertThat(violations.get(0).path("rule").asText()).isEqualTo("I8");
        assertThat(violations.get(0).path("name").asText()).isEqualTo("allowance drift");
        assertThat(violations.get(0).path("detail").asText()).contains(userId.toString());
    }

    @Test
    void onARedisFirstEventItReportsTheStreamLagUntilTheConsumerHasAppliedIt() {
        // The consumer thread is off in tests, so a hold made over HTTP stays in the stream (unapplied)
        // until the test drains it: consumer lag at its worst.
        jdbc.sql("UPDATE events SET strategy = 'REDIS_FIRST' WHERE id = ?").param(EVENT).update();
        Response hold = exchange(HttpMethod.POST, "/events/" + EVENT + "/holds",
                new HttpEntity<>(Map.of("seatIds", List.of(SEAT)), json(user)));
        assertThat(hold.status()).as(String.valueOf(hold.body())).isEqualTo(201);

        Response lagging = check("eventId=" + EVENT + "&grace=PT0S", admin);

        assertThat(lagging.status()).isEqualTo(200);
        assertThat(lagging.body().path("strategy").asText()).isEqualTo("REDIS_FIRST");
        assertThat(lagging.body().path("unappliedStreamEntries").asBoolean()).as("the HELD entry").isTrue();

        RedisFirstTestAccess.drainStream(context);
        Response drained = check("eventId=" + EVENT + "&grace=PT0S", admin);

        assertThat(drained.status()).isEqualTo(200);
        assertThat(drained.body().path("unappliedStreamEntries").asBoolean()).isFalse();
        assertThat(drained.body().path("violations")).as(String.valueOf(drained.body())).isEmpty();
        assertThat(jdbc.sql("SELECT status FROM event_seats WHERE event_id = ? AND seat_id = ?")
                .params(EVENT, SEAT).query(String.class).single()).as("applied to Postgres").isEqualTo("HELD");
    }

    // --- 400, 404 ----------------------------------------------------------------------------------

    @Test
    void aMissingOrMalformedEventIdIs400() {
        for (String query : List.of("", "eventId=not-a-uuid")) {
            Response response = check(query, admin);

            assertThat(response.status()).as(query).isEqualTo(400);
            assertThat(response.code()).as(query).isEqualTo("VALIDATION_FAILED");
        }
    }

    @Test
    void aMalformedOrNegativeGraceIs400() {
        for (String grace : List.of("30s", "-PT1S")) {
            Response response = check("eventId=" + EVENT + "&grace=" + grace, admin);

            assertThat(response.status()).as(grace).isEqualTo(400);
            assertThat(response.code()).as(grace).isEqualTo("VALIDATION_FAILED");
            assertThat(response.body().path("errors").get(0).path("field").asText()).isEqualTo("grace");
        }
    }

    @Test
    void anUnknownEventIs404() {
        Response response = check("eventId=" + UUID.randomUUID(), admin);

        assertThat(response.status()).isEqualTo(404);
        assertThat(response.code()).isEqualTo("EVENT_NOT_FOUND");
    }

    // --- helpers -----------------------------------------------------------------------------------

    private Response check(String query, String token) {
        return get("/admin/invariants" + (query.isEmpty() ? "" : "?" + query), token);
    }

    private static HttpHeaders json(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        return headers;
    }

    private static List<String> texts(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(node -> values.add(node.asText()));
        return values;
    }
}
