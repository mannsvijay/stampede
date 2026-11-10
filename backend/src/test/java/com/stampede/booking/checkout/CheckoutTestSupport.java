package com.stampede.booking.checkout;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.stampede.inventory.strategy.HoldInvariants;
import com.stampede.testsupport.ApiTestSupport;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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

    /**
     * The hold invariants (I1-I3, I8 counting bought seats) plus the order and ticket rules below. Each query
     * returns the rows that break its rule, so empty means it holds. Candidates for the M1.7 checker.
     */
    void assertInvariants() {
        List<String> violations = new ArrayList<>(HoldInvariants.check(jdbc, event));
        violations.addAll(run("O1 sold seat without exactly one active item in its PAID order", """
                SELECT es.seat_id::text || ' is SOLD to ' || es.order_id::text || ' with '
                       || count(oi.order_id) FILTER (WHERE oi.active) || ' active items, '
                       || count(oi.order_id) FILTER (WHERE oi.active AND o.status = 'PAID' AND o.id = es.order_id)
                       || ' of them in its PAID order'
                FROM event_seats es
                LEFT JOIN order_items oi ON oi.event_id = es.event_id AND oi.seat_id = es.seat_id
                LEFT JOIN orders o ON o.id = oi.order_id
                WHERE es.event_id = ? AND es.status = 'SOLD'
                GROUP BY es.seat_id, es.order_id
                HAVING count(oi.order_id) FILTER (WHERE oi.active) <> 1
                    OR count(oi.order_id) FILTER (WHERE oi.active AND o.status = 'PAID' AND o.id = es.order_id) <> 1
                """));
        violations.addAll(run("O2 PAID order whose seat isn't SOLD to it", """
                SELECT 'order ' || o.id::text || ' is PAID but seat ' || oi.seat_id::text || ' is ' || es.status
                       || ' owned by ' || coalesce(es.order_id::text, 'no order')
                       || CASE WHEN oi.active THEN '' ELSE ' (item inactive)' END
                FROM orders o
                JOIN order_items oi ON oi.order_id = o.id
                JOIN event_seats es ON es.event_id = oi.event_id AND es.seat_id = oi.seat_id
                WHERE o.event_id = ? AND o.status = 'PAID'
                  AND (es.status <> 'SOLD' OR es.order_id IS DISTINCT FROM o.id OR NOT oi.active)
                """));
        violations.addAll(run("O3 active item in an order that isn't PENDING or PAID", """
                SELECT 'order ' || o.id::text || ' is ' || o.status || ' but seat ' || oi.seat_id::text
                       || ' is still active'
                FROM orders o JOIN order_items oi ON oi.order_id = o.id
                WHERE o.event_id = ? AND oi.active AND o.status NOT IN ('PENDING', 'PAID')
                """));
        violations.addAll(run("O4 money: PAID needs one SUCCEEDED payment of its total, CANCELLED none", """
                SELECT 'order ' || o.id::text || ' is ' || o.status || ' (total ' || o.total_paise || ', items '
                       || (SELECT coalesce(sum(price_paise), 0) FROM order_items WHERE order_id = o.id)
                       || ') with succeeded payments ' || coalesce(array_agg(p.amount_paise)
                              FILTER (WHERE p.status = 'SUCCEEDED')::text, '{}')
                FROM orders o
                LEFT JOIN payments p ON p.order_id = o.id
                WHERE o.event_id = ?
                GROUP BY o.id, o.status, o.total_paise
                HAVING (o.status = 'PAID' AND (count(p.id) FILTER (WHERE p.status = 'SUCCEEDED') <> 1
                            OR min(p.amount_paise) FILTER (WHERE p.status = 'SUCCEEDED') <> o.total_paise
                            OR o.total_paise <> (SELECT coalesce(sum(price_paise), 0) FROM order_items
                                                 WHERE order_id = o.id)))
                    OR (o.status = 'CANCELLED' AND count(p.id) FILTER (WHERE p.status = 'SUCCEEDED') > 0)
                """));
        violations.addAll(run("O5 CONVERTED hold without a PAID order, or PAID order whose hold isn't CONVERTED", """
                SELECT 'hold ' || h.id::text || ' is ' || h.status || ', its order is '
                       || coalesce(o.status, 'missing')
                FROM holds h
                FULL JOIN orders o ON o.hold_id = h.id
                WHERE coalesce(h.event_id, o.event_id) = ?
                  AND ((h.status = 'CONVERTED' AND o.status IS DISTINCT FROM 'PAID')
                    OR (o.status = 'PAID' AND h.status IS DISTINCT FROM 'CONVERTED'))
                """));
        violations.addAll(run("T1 live ticket whose seat isn't SOLD to its PAID order, or held by someone else", """
                SELECT 'ticket ' || t.id::text || ' for seat ' || t.seat_id::text || ' is in order '
                       || t.order_id::text || ' (' || o.status || '), seat is ' || es.status || ' owned by '
                       || coalesce(es.order_id::text, 'no order')
                       || CASE WHEN t.holder_user_id = o.user_id THEN '' ELSE ', holder is not the buyer' END
                FROM tickets t
                JOIN orders o ON o.id = t.order_id
                JOIN event_seats es ON es.event_id = t.event_id AND es.seat_id = t.seat_id
                WHERE t.event_id = ? AND t.status <> 'REVOKED'
                  AND (o.status <> 'PAID' OR es.status <> 'SOLD' OR es.order_id IS DISTINCT FROM t.order_id
                       OR t.holder_user_id <> o.user_id)
                """));
        violations.addAll(run("T2 order at TICKETS_ISSUED without exactly one live ticket per active item", """
                SELECT 'order ' || o.id::text || ' (' || o.status || '/' || o.saga_state || ') has '
                       || (SELECT count(*) FROM order_items oi WHERE oi.order_id = o.id AND oi.active)
                       || ' active items and live tickets for '
                       || (SELECT count(*) FROM order_items oi JOIN tickets t ON t.order_id = o.id
                               AND t.event_id = oi.event_id AND t.seat_id = oi.seat_id AND t.status <> 'REVOKED'
                           WHERE oi.order_id = o.id AND oi.active)
                       || ' of them, ' || (SELECT count(*) FROM tickets t WHERE t.order_id = o.id
                                              AND t.status <> 'REVOKED') || ' in all'
                FROM orders o
                WHERE o.event_id = ? AND o.saga_state = 'TICKETS_ISSUED'
                  AND (o.status <> 'PAID'
                    OR (SELECT count(*) FROM order_items oi WHERE oi.order_id = o.id AND oi.active)
                       <> (SELECT count(*) FROM order_items oi JOIN tickets t ON t.order_id = o.id
                               AND t.event_id = oi.event_id AND t.seat_id = oi.seat_id AND t.status <> 'REVOKED'
                           WHERE oi.order_id = o.id AND oi.active)
                    OR (SELECT count(*) FROM tickets t WHERE t.order_id = o.id AND t.status <> 'REVOKED')
                       <> (SELECT count(*) FROM order_items oi WHERE oi.order_id = o.id AND oi.active))
                """));
        assertThat(violations).as("invariant violations").isEmpty();
    }

    /** No checkout left anything half-done: every order ended PAID or CANCELLED. */
    void assertNoPendingOrders() {
        assertThat(count("SELECT count(*) FROM orders WHERE event_id = ? AND status = 'PENDING'", event))
                .as("PENDING orders").isZero();
    }

    private List<String> run(String rule, String sql) {
        return jdbc.sql(sql).param(event).query(String.class).list().stream()
                .map(detail -> rule + ": " + detail)
                .toList();
    }
}
