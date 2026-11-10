package com.stampede.inventory.holds;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.stampede.testsupport.ApiTestSupport;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;

/**
 * POST /events/{id}/holds and DELETE /holds/{id} over HTTP (M1.4, D-016): auth, ownership, and the
 * mapping of every outcome to its status and problem code. The strategy's own behaviour is covered by
 * PessimisticInventoryStrategyTest; this tests the API around it.
 */
class HoldApiTest extends ApiTestSupport {

    static final UUID VENUE = UUID.fromString("00000000-0000-4000-8000-0000000001a1");
    static final UUID SECTION = UUID.fromString("00000000-0000-4000-8000-0000000001a2");
    static final UUID EVENT = UUID.fromString("00000000-0000-4000-8000-0000000001a3");
    static final UUID TIER = UUID.fromString("00000000-0000-4000-8000-0000000001a4");
    static final List<UUID> SEATS = List.of(
            UUID.fromString("10000000-0000-4000-8000-0000000001b1"),
            UUID.fromString("90000000-0000-4000-8000-0000000001b2"),
            UUID.fromString("30000000-0000-4000-8000-0000000001b3"),
            UUID.fromString("b0000000-0000-4000-8000-0000000001b4"),
            UUID.fromString("50000000-0000-4000-8000-0000000001b5"));

    private String alice;
    private String bob;
    private UUID aliceId;

    @BeforeEach
    void insertEventAndLogIn() {
        jdbc.sql("INSERT INTO venues (id, name, layout_json) VALUES (?, 'Hold venue', '{}')").param(VENUE).update();
        jdbc.sql("INSERT INTO sections (id, venue_id, code, default_tier) VALUES (?, ?, 'A', 'GOLD')")
                .params(SECTION, VENUE).update();
        jdbc.sql("""
                INSERT INTO events (id, venue_id, title, starts_at, drop_at, status, per_user_limit, strategy)
                VALUES (?, ?, 'Hold test event', now() + interval '30 days', now() - interval '1 hour',
                        'LIVE', 4, 'PESSIMISTIC')
                """).params(EVENT, VENUE).update();
        jdbc.sql("INSERT INTO price_tiers (id, event_id, name, price_paise) VALUES (?, ?, 'GOLD', 150000)")
                .params(TIER, EVENT).update();
        int number = 1;
        for (UUID seat : SEATS) {
            jdbc.sql("INSERT INTO seats (id, section_id, row_label, number, x, y) VALUES (?, ?, 'A', ?, 0, 0)")
                    .params(seat, SECTION, number++).update();
            jdbc.sql("INSERT INTO event_seats (event_id, seat_id, tier_id, status) VALUES (?, ?, ?, 'AVAILABLE')")
                    .params(EVENT, seat, TIER).update();
        }
        String aliceEmail = uniqueEmail();
        alice = registerAndLogin(aliceEmail).accessToken();
        bob = registerAndLogin(uniqueEmail()).accessToken();
        aliceId = jdbc.sql("SELECT id FROM users WHERE email = ?").param(aliceEmail).query(UUID.class).single();
    }

    /** Other tests share this database (and GET /events must only list the demo event there). */
    @AfterEach
    void deleteEvent() {
        jdbc.sql("DELETE FROM tickets WHERE event_id = ?").param(EVENT).update();
        jdbc.sql("DELETE FROM payments WHERE order_id IN (SELECT id FROM orders WHERE event_id = ?)")
                .param(EVENT).update();
        jdbc.sql("DELETE FROM order_items WHERE event_id = ?").param(EVENT).update();
        jdbc.sql("DELETE FROM event_seats WHERE event_id = ?").param(EVENT).update();
        jdbc.sql("DELETE FROM orders WHERE event_id = ?").param(EVENT).update();
        jdbc.sql("DELETE FROM holds WHERE event_id = ?").param(EVENT).update();
        jdbc.sql("DELETE FROM user_event_allowance WHERE event_id = ?").param(EVENT).update();
        jdbc.sql("DELETE FROM price_tiers WHERE event_id = ?").param(EVENT).update();
        jdbc.sql("DELETE FROM events WHERE id = ?").param(EVENT).update();
        jdbc.sql("DELETE FROM seats WHERE section_id = ?").param(SECTION).update();
        jdbc.sql("DELETE FROM sections WHERE id = ?").param(SECTION).update();
        jdbc.sql("DELETE FROM venues WHERE id = ?").param(VENUE).update();
    }

    // --- POST /events/{id}/holds -----------------------------------------------------------------

    @Test
    void holdWithoutATokenIs401() {
        Response response = postHold(EVENT, null, SEATS.get(0));

        assertThat(response.status()).isEqualTo(401);
        assertThat(response.code()).isEqualTo("UNAUTHENTICATED");
        assertThat(heldSeats()).isZero();
    }

    @Test
    void holdReturns201WithLocationAndTheHold() {
        Response response = postHold(EVENT, alice, SEATS.get(1), SEATS.get(0));

        assertThat(response.status()).isEqualTo(201);
        UUID holdId = UUID.fromString(response.body().path("holdId").asText());
        assertThat(response.headers().getLocation()).hasToString("/holds/" + holdId);
        assertThat(response.body().path("eventId").asText()).isEqualTo(EVENT.toString());
        assertThat(uuids(response.body().path("seatIds"))).containsExactlyInAnyOrder(SEATS.get(0), SEATS.get(1));
        Instant expiresAt = Instant.parse(response.body().path("expiresAt").asText());
        Instant serverTime = Instant.parse(response.body().path("serverTime").asText());
        assertThat(Duration.between(serverTime, expiresAt)).isBetween(Duration.ofMinutes(4), Duration.ofMinutes(5));

        assertThat(jdbc.sql("SELECT user_id FROM holds WHERE id = ?").param(holdId).query(UUID.class).single())
                .isEqualTo(aliceId);
        assertThat(used(aliceId)).isEqualTo(2);
    }

    @Test
    void retryingTheSameSeatsReturnsTheExistingHoldWith200() {
        // D-016: a client that timed out and retries must get its hold back, not SEAT_TAKEN.
        Response first = postHold(EVENT, alice, SEATS.get(0), SEATS.get(1));
        Response retry = postHold(EVENT, alice, SEATS.get(1), SEATS.get(0));  // same set, other order

        assertThat(retry.status()).isEqualTo(200);
        assertThat(retry.body().path("holdId").asText()).isEqualTo(first.body().path("holdId").asText());
        assertThat(retry.body().path("expiresAt").asText()).isEqualTo(first.body().path("expiresAt").asText());
        assertThat(jdbc.sql("SELECT count(*) FROM holds WHERE event_id = ?").param(EVENT).query(Long.class).single())
                .isEqualTo(1);
        assertThat(used(aliceId)).as("counted once").isEqualTo(2);
    }

    @Test
    void overlappingButDifferentSeatsOfYourOwnHoldAreSeatTaken() {
        postHold(EVENT, alice, SEATS.get(0), SEATS.get(1));

        Response response = postHold(EVENT, alice, SEATS.get(1), SEATS.get(2));

        assertThat(response.status()).isEqualTo(409);
        assertThat(response.code()).isEqualTo("SEAT_TAKEN");
        assertThat(uuids(response.body().path("seatIds"))).containsExactly(SEATS.get(1));
    }

    @Test
    void aSeatHeldBySomeoneElseIs409SeatTakenWithTakenAgoMs() {
        postHold(EVENT, alice, SEATS.get(0));

        Response response = postHold(EVENT, bob, SEATS.get(2), SEATS.get(0));

        assertThat(response.status()).isEqualTo(409);
        assertThat(response.headers().getContentType()).hasToString("application/problem+json");
        assertThat(response.code()).isEqualTo("SEAT_TAKEN");
        assertThat(uuids(response.body().path("seatIds"))).containsExactly(SEATS.get(0));
        assertThat(response.body().path("takenAgoMs").isIntegralNumber()).isTrue();
        assertThat(response.body().path("takenAgoMs").asLong()).isBetween(0L, 60_000L);
        assertThat(seatStatus(SEATS.get(2))).as("all-or-nothing").isEqualTo("AVAILABLE");
    }

    @Test
    void goingOverTheLimitIs409LimitExceeded() {
        postHold(EVENT, alice, SEATS.get(0), SEATS.get(1), SEATS.get(2));

        Response response = postHold(EVENT, alice, SEATS.get(3), SEATS.get(4));

        assertThat(response.status()).isEqualTo(409);
        assertThat(response.code()).isEqualTo("LIMIT_EXCEEDED");
        assertThat(response.body().path("limit").asInt()).isEqualTo(4);
        assertThat(uuids(response.body().path("seatIds"))).containsExactly(SEATS.get(3), SEATS.get(4));
        assertThat(seatStatus(SEATS.get(3))).isEqualTo("AVAILABLE");
        assertThat(used(aliceId)).isEqualTo(3);
    }

    @Test
    void invalidSeatListsAre400() {
        Response duplicate = postHold(EVENT, alice, SEATS.get(0), SEATS.get(0));
        assertThat(duplicate.status()).isEqualTo(400);
        assertThat(duplicate.code()).isEqualTo("VALIDATION_FAILED");
        assertThat(duplicate.body().path("errors").get(0).path("field").asText()).isEqualTo("seatIds");

        Response empty = postHold(EVENT, alice);
        assertThat(empty.status()).isEqualTo(400);
        assertThat(empty.code()).isEqualTo("VALIDATION_FAILED");

        List<UUID> tooMany = new ArrayList<>();
        for (int i = 0; i <= HoldController.MAX_SEATS_PER_REQUEST; i++) {
            tooMany.add(UUID.randomUUID());
        }
        Response large = postHold(EVENT, alice, tooMany.toArray(UUID[]::new));
        assertThat(large.status()).isEqualTo(400);

        Response missing = exchange(HttpMethod.POST, "/events/" + EVENT + "/holds",
                new HttpEntity<>(Map.of(), jsonHeaders(alice)));
        assertThat(missing.status()).isEqualTo(400);
        assertThat(missing.code()).isEqualTo("VALIDATION_FAILED");

        assertThat(heldSeats()).isZero();
    }

    @Test
    void aSeatOfNoOrAnotherEventIs400() {
        UUID unknown = UUID.randomUUID();

        Response response = postHold(EVENT, alice, SEATS.get(0), unknown);

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.code()).isEqualTo("VALIDATION_FAILED");
        assertThat(response.body().path("errors").get(0).path("message").asText()).contains(unknown.toString());
        assertThat(heldSeats()).isZero();
    }

    @Test
    void unknownOrDraftEventIs404AndAMalformedIdIs400() {
        assertThat(postHold(UUID.randomUUID(), alice, SEATS.get(0)).code()).isEqualTo("EVENT_NOT_FOUND");

        jdbc.sql("UPDATE events SET status = 'DRAFT' WHERE id = ?").param(EVENT).update();
        Response draft = postHold(EVENT, alice, SEATS.get(0));
        assertThat(draft.status()).isEqualTo(404);
        assertThat(draft.code()).isEqualTo("EVENT_NOT_FOUND");

        Response malformed = exchange(HttpMethod.POST, "/events/not-a-uuid/holds",
                new HttpEntity<>(Map.of("seatIds", List.of(SEATS.get(0))), jsonHeaders(alice)));
        assertThat(malformed.status()).isEqualTo(400);
        assertThat(malformed.code()).isEqualTo("VALIDATION_FAILED");
    }

    @Test
    void beforeTheDropOrAfterTheSaleIs409SaleNotOpen() {
        jdbc.sql("UPDATE events SET status = 'SCHEDULED', drop_at = now() + interval '1 hour' WHERE id = ?")
                .param(EVENT).update();
        Response early = postHold(EVENT, alice, SEATS.get(0));
        assertThat(early.status()).isEqualTo(409);
        assertThat(early.code()).isEqualTo("SALE_NOT_OPEN");

        jdbc.sql("UPDATE events SET drop_at = now() - interval '1 minute' WHERE id = ?").param(EVENT).update();
        assertThat(postHold(EVENT, alice, SEATS.get(0)).status()).as("SCHEDULED after its drop").isEqualTo(201);

        for (String status : List.of("PAUSED", "SOLD_OUT", "ENDED")) {
            jdbc.sql("UPDATE events SET status = ? WHERE id = ?").params(status, EVENT).update();
            assertThat(postHold(EVENT, alice, SEATS.get(1)).code()).as(status).isEqualTo("SALE_NOT_OPEN");
        }
    }

    // --- DELETE /holds/{id} ----------------------------------------------------------------------

    @Test
    void deleteWithoutATokenIs401() {
        UUID holdId = holdId(postHold(EVENT, alice, SEATS.get(0)));

        Response response = request(HttpMethod.DELETE, "/holds/" + holdId, null);

        assertThat(response.status()).isEqualTo(401);
        assertThat(holdStatus(holdId)).isEqualTo("ACTIVE");
    }

    @Test
    void deletingYourHoldIs204AndFreesSeatsAndAllowance() {
        UUID holdId = holdId(postHold(EVENT, alice, SEATS.get(0), SEATS.get(1)));

        Response response = request(HttpMethod.DELETE, "/holds/" + holdId, alice);

        assertThat(response.status()).isEqualTo(204);
        assertThat(holdStatus(holdId)).isEqualTo("RELEASED");
        assertThat(seatStatus(SEATS.get(0))).isEqualTo("AVAILABLE");
        assertThat(used(aliceId)).isZero();

        assertThat(request(HttpMethod.DELETE, "/holds/" + holdId, alice).status())
                .as("deleting again is still 204 (idempotent)").isEqualTo(204);
    }

    @Test
    void deletingYourExpiredHoldIs204() {
        UUID holdId = holdId(postHold(EVENT, alice, SEATS.get(0)));
        jdbc.sql("UPDATE event_seats SET status = 'AVAILABLE', hold_id = NULL WHERE event_id = ?").param(EVENT).update();
        jdbc.sql("UPDATE holds SET status = 'EXPIRED' WHERE id = ?").param(holdId).update();
        // An expiry gives the seat back to the allowance (D-016), as the real release does.
        jdbc.sql("UPDATE user_event_allowance SET used = used - 1 WHERE event_id = ? AND user_id = ?")
                .params(EVENT, aliceId).update();

        assertThat(request(HttpMethod.DELETE, "/holds/" + holdId, alice).status()).isEqualTo(204);
    }

    @Test
    void deletingSomeoneElsesHoldIs404AndChangesNothing() {
        // No IDOR: knowing a hold id is not enough, and the answer is the same as for an unknown id.
        UUID holdId = holdId(postHold(EVENT, alice, SEATS.get(0)));

        Response response = request(HttpMethod.DELETE, "/holds/" + holdId, bob);
        Response unknown = request(HttpMethod.DELETE, "/holds/" + UUID.randomUUID(), bob);

        assertThat(response.status()).isEqualTo(404);
        assertThat(response.code()).isEqualTo("HOLD_NOT_FOUND");
        assertThat(unknown.status()).isEqualTo(404);
        assertThat(unknown.code()).isEqualTo("HOLD_NOT_FOUND");
        assertThat(holdStatus(holdId)).isEqualTo("ACTIVE");
        assertThat(seatStatus(SEATS.get(0))).isEqualTo("HELD");
        assertThat(used(aliceId)).isEqualTo(1);
    }

    @Test
    void deletingYourConvertedHoldIs409HoldConverted() {
        UUID holdId = holdId(postHold(EVENT, alice, SEATS.get(0)));
        // Converted the real way, by a checkout, so the hold has its PAID order and the seat is SOLD.
        Response order = exchange(HttpMethod.POST, "/orders",
                new HttpEntity<>(Map.of("holdId", holdId), jsonHeaders(alice)));
        assertThat(order.status()).as("checkout: %s", order.body()).isEqualTo(201);

        Response response = request(HttpMethod.DELETE, "/holds/" + holdId, alice);

        assertThat(response.status()).isEqualTo(409);
        assertThat(response.code()).isEqualTo("HOLD_CONVERTED");
        assertThat(holdStatus(holdId)).isEqualTo("CONVERTED");
        assertThat(used(aliceId)).as("a bought seat keeps counting").isEqualTo(1);
    }

    @Test
    void deleteWithAMalformedIdIs400() {
        Response response = request(HttpMethod.DELETE, "/holds/not-a-uuid", alice);

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.code()).isEqualTo("VALIDATION_FAILED");
    }

    // --- helpers ---------------------------------------------------------------------------------

    private Response postHold(UUID eventId, String token, UUID... seats) {
        return exchange(HttpMethod.POST, "/events/" + eventId + "/holds",
                new HttpEntity<>(Map.of("seatIds", List.of(seats)), jsonHeaders(token)));
    }

    private static HttpHeaders jsonHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return headers;
    }

    private static UUID holdId(Response response) {
        assertThat(response.status()).isEqualTo(201);
        return UUID.fromString(response.body().path("holdId").asText());
    }

    private static List<UUID> uuids(JsonNode array) {
        return StreamSupport.stream(array.spliterator(), false).map(n -> UUID.fromString(n.asText())).toList();
    }

    private String holdStatus(UUID holdId) {
        return jdbc.sql("SELECT status FROM holds WHERE id = ?").param(holdId).query(String.class).single();
    }

    private String seatStatus(UUID seatId) {
        return jdbc.sql("SELECT status FROM event_seats WHERE event_id = ? AND seat_id = ?")
                .params(EVENT, seatId).query(String.class).single();
    }

    private long heldSeats() {
        return jdbc.sql("SELECT count(*) FROM event_seats WHERE event_id = ? AND status <> 'AVAILABLE'")
                .param(EVENT).query(Long.class).single();
    }

    private int used(UUID user) {
        return jdbc.sql("SELECT coalesce(max(used), 0) FROM user_event_allowance WHERE event_id = ? AND user_id = ?")
                .params(EVENT, user).query(Integer.class).single();
    }
}
