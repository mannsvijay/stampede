package com.stampede.booking.checkout;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.stampede.control.invariants.InvariantChecker;
import com.stampede.control.invariants.Violation;
import com.stampede.testsupport.ApiTestSupport;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;

/**
 * Shared fixture for the checkout tests (M1.5): one LIVE pessimistic event whose drop has passed,
 * {@link #SEAT_COUNT} seats in two tiers (even-numbered seats GOLD, odd ones SILVER), two logged-in
 * buyers, and the order invariants every test ends with. Fresh ids per test, cleaned up after.
 */
abstract class CheckoutTestSupport extends ApiTestSupport {

    static final int SEAT_COUNT = 24;
    static final int PER_USER_LIMIT = 4;
    static final long GOLD_PAISE = 150_000;
    static final long SILVER_PAISE = 90_000;

    @Autowired
    InvariantChecker invariants;

    UUID venue;
    UUID section;
    UUID event;
    List<UUID> seats;
    String alice;
    String bob;
    UUID aliceId;
    UUID bobId;

    @BeforeEach
    void insertEventAndLogIn() {
        venue = UUID.randomUUID();
        section = UUID.randomUUID();
        event = UUID.randomUUID();
        UUID gold = UUID.randomUUID();
        UUID silver = UUID.randomUUID();
        jdbc.sql("INSERT INTO venues (id, name, layout_json) VALUES (?, 'Checkout venue', '{}')").param(venue).update();
        jdbc.sql("INSERT INTO sections (id, venue_id, code, default_tier) VALUES (?, ?, 'A', 'GOLD')")
                .params(section, venue).update();
        jdbc.sql("""
                INSERT INTO events (id, venue_id, title, starts_at, drop_at, status, per_user_limit, strategy)
                VALUES (?, ?, 'Checkout test event', now() + interval '30 days', now() - interval '1 hour',
                        'LIVE', ?, 'PESSIMISTIC')
                """).params(event, venue, PER_USER_LIMIT).update();
        jdbc.sql("INSERT INTO price_tiers (id, event_id, name, price_paise) VALUES (?, ?, 'GOLD', ?)")
                .params(gold, event, GOLD_PAISE).update();
        jdbc.sql("INSERT INTO price_tiers (id, event_id, name, price_paise) VALUES (?, ?, 'SILVER', ?)")
                .params(silver, event, SILVER_PAISE).update();
        seats = new ArrayList<>();
        for (int i = 0; i < SEAT_COUNT; i++) {
            UUID seat = UUID.randomUUID();
            seats.add(seat);
            jdbc.sql("INSERT INTO seats (id, section_id, row_label, number, x, y) VALUES (?, ?, 'A', ?, 0, 0)")
                    .params(seat, section, i + 1).update();
            jdbc.sql("INSERT INTO event_seats (event_id, seat_id, tier_id, status) VALUES (?, ?, ?, 'AVAILABLE')")
                    .params(event, seat, i % 2 == 0 ? gold : silver).update();
        }
        String aliceEmail = uniqueEmail();
        String bobEmail = uniqueEmail();
        alice = registerAndLogin(aliceEmail).accessToken();
        bob = registerAndLogin(bobEmail).accessToken();
        aliceId = userId(aliceEmail);
        bobId = userId(bobEmail);
    }

    /** Other tests share this database (and GET /events must only list the demo event there). */
    @AfterEach
    void deleteEvent() {
        jdbc.sql("DELETE FROM tickets WHERE event_id = ?").param(event).update();
        jdbc.sql("DELETE FROM payments WHERE order_id IN (SELECT id FROM orders WHERE event_id = ?)")
                .param(event).update();
        jdbc.sql("DELETE FROM order_items WHERE event_id = ?").param(event).update();
        jdbc.sql("DELETE FROM event_seats WHERE event_id = ?").param(event).update();
        jdbc.sql("DELETE FROM orders WHERE event_id = ?").param(event).update();
        jdbc.sql("DELETE FROM holds WHERE event_id = ?").param(event).update();
        jdbc.sql("DELETE FROM user_event_allowance WHERE event_id = ?").param(event).update();
        jdbc.sql("DELETE FROM price_tiers WHERE event_id = ?").param(event).update();
        jdbc.sql("DELETE FROM events WHERE id = ?").param(event).update();
        jdbc.sql("DELETE FROM seats WHERE section_id = ?").param(section).update();
        jdbc.sql("DELETE FROM sections WHERE id = ?").param(section).update();
        jdbc.sql("DELETE FROM venues WHERE id = ?").param(venue).update();
    }

    // --- HTTP ------------------------------------------------------------------------------------

    /** POST /events/{id}/holds; asserts 201 and returns the hold id. */
    UUID hold(String token, UUID... seatIds) {
        Response response = exchange(HttpMethod.POST, "/events/" + event + "/holds",
                new HttpEntity<>(Map.of("seatIds", List.of(seatIds)), jsonHeaders(token)));
        assertThat(response.status()).as("hold: %s", response.body()).isEqualTo(201);
        return UUID.fromString(response.body().path("holdId").asText());
    }

    Response postOrder(String token, UUID holdId) {
        return exchange(HttpMethod.POST, "/orders", new HttpEntity<>(Map.of("holdId", holdId), jsonHeaders(token)));
    }

    Response getOrder(String token, UUID orderId) {
        return get("/orders/" + orderId, token);
    }

    static HttpHeaders jsonHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return headers;
    }

    static UUID orderId(Response response) {
        return UUID.fromString(response.body().path("orderId").asText());
    }

    static List<UUID> uuids(JsonNode array) {
        return StreamSupport.stream(array.spliterator(), false).map(node -> UUID.fromString(node.asText())).toList();
    }

    // --- database reads --------------------------------------------------------------------------

    String seatStatus(UUID seatId) {
        return jdbc.sql("SELECT status FROM event_seats WHERE event_id = ? AND seat_id = ?")
                .params(event, seatId).query(String.class).single();
    }

    UUID seatOrder(UUID seatId) {
        return jdbc.sql("SELECT order_id FROM event_seats WHERE event_id = ? AND seat_id = ?")
                .params(event, seatId).query(UUID.class).optional().orElse(null);
    }

    String holdStatus(UUID holdId) {
        return jdbc.sql("SELECT status FROM holds WHERE id = ?").param(holdId).query(String.class).single();
    }

    long count(String sql, Object... params) {
        return jdbc.sql(sql).params(params).query(Long.class).single();
    }

    long ordersOfEvent() {
        return count("SELECT count(*) FROM orders WHERE event_id = ?", event);
    }

    long paymentsOfEvent() {
        return count("SELECT count(*) FROM payments p JOIN orders o ON o.id = p.order_id WHERE o.event_id = ?", event);
    }

    String orderStatus(UUID orderId) {
        return jdbc.sql("SELECT status FROM orders WHERE id = ?").param(orderId).query(String.class).single();
    }

    List<String> paymentStatuses(UUID orderId) {
        return jdbc.sql("SELECT status FROM payments WHERE order_id = ? ORDER BY created_at")
                .param(orderId).query(String.class).list();
    }

    int used(UUID userId) {
        return jdbc.sql("SELECT used FROM user_event_allowance WHERE event_id = ? AND user_id = ?")
                .params(event, userId).query(Integer.class).optional().orElse(0);
    }

    private UUID userId(String email) {
        return jdbc.sql("SELECT id FROM users WHERE email = ?").param(email).query(UUID.class).single();
    }

    long ticketsOfEvent() {
        return count("SELECT count(*) FROM tickets WHERE event_id = ?", event);
    }

    long ticketsOfOrder(UUID orderId) {
        return count("SELECT count(*) FROM tickets WHERE order_id = ?", orderId);
    }

    String sagaState(UUID orderId) {
        return jdbc.sql("SELECT saga_state FROM orders WHERE id = ?").param(orderId).query(String.class).single();
    }

    // --- invariants ------------------------------------------------------------------------------

    /** Every rule of the invariant checker (M1.7) holds for this test's event, strictly. */
    void assertInvariants() {
        assertThat(invariantViolations()).as("invariant violations").isEmpty();
    }

    /**
     * Every rule's violations for this test's event, each prefixed with its rule; empty when all hold.
     * Strict (no grace): the request under test has returned, so nothing may be mid-checkout.
     */
    List<String> invariantViolations() {
        return invariants.check(event, Duration.ZERO).stream().map(Violation::toString).toList();
    }

    /** No checkout left anything half-done: every order ended PAID or CANCELLED. */
    void assertNoPendingOrders() {
        assertThat(count("SELECT count(*) FROM orders WHERE event_id = ? AND status = 'PENDING'", event))
                .as("PENDING orders").isZero();
    }
}
