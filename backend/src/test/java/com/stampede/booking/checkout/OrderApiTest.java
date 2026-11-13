package com.stampede.booking.checkout;

import static org.assertj.core.api.Assertions.assertThat;

import com.stampede.inventory.strategy.RedisFirstTestAccess;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;

/**
 * POST /orders and GET /orders/{id} over HTTP (M1.5, D-017): the happy path from hold to PAID,
 * retries, ownership (no IDOR) and the hold states that can't become an order. Failure injection is in
 * CheckoutFailureTest, races in OrderConcurrencyTest.
 */
class OrderApiTest extends CheckoutTestSupport {

    @Autowired
    ApplicationContext context;

    @AfterEach
    void invariantsHold() {
        assertInvariants();
        assertNoPendingOrders();
    }

    // --- POST /orders: happy path ----------------------------------------------------------------

    @Test
    void orderingAHoldSellsItsSeatsAndReturns201Paid() {
        UUID gold = seats.get(0);
        UUID silver = seats.get(1);
        UUID holdId = hold(alice, gold, silver);

        Response response = postOrder(alice, holdId);

        assertThat(response.status()).as(response.body().toString()).isEqualTo(201);
        UUID orderId = orderId(response);
        assertThat(response.headers().getLocation()).hasToString("/orders/" + orderId);
        assertThat(response.body().path("status").asText()).isEqualTo("PAID");
        assertThat(response.body().path("eventId").asText()).isEqualTo(event.toString());
        assertThat(response.body().path("holdId").asText()).isEqualTo(holdId.toString());
        assertThat(response.body().path("totalPaise").asLong()).isEqualTo(GOLD_PAISE + SILVER_PAISE);
        assertThat(response.body().path("items")).hasSize(2);
        response.body().path("items").forEach(item -> {
            UUID seat = UUID.fromString(item.path("seatId").asText());
            assertThat(item.path("pricePaise").asLong()).isEqualTo(seat.equals(gold) ? GOLD_PAISE : SILVER_PAISE);
        });
        assertThat(Instant.parse(response.body().path("createdAt").asText())).isBefore(Instant.now().plusSeconds(5));

        // Seats SOLD to the order, hold CONVERTED, payment taken, allowance still counting the seats.
        assertThat(seatStatus(gold)).isEqualTo("SOLD");
        assertThat(seatStatus(silver)).isEqualTo("SOLD");
        assertThat(seatOrder(gold)).isEqualTo(orderId);
        assertThat(seatOrder(silver)).isEqualTo(orderId);
        assertThat(holdStatus(holdId)).isEqualTo("CONVERTED");
        assertThat(paymentStatuses(orderId)).containsExactly("SUCCEEDED");
        assertThat(used(aliceId)).as("bought seats keep counting (D-016)").isEqualTo(2);
        assertThat(count("SELECT count(*) FROM order_items WHERE order_id = ? AND active", orderId)).isEqualTo(2);
    }

    @Test
    void aRedisFirstHoldChecksOutToPaidWithTicketsBeforeTheConsumerHasPersistedIt() {
        // M2.2c. The stream consumer doesn't run in tests, so the hold exists in Redis only when
        // POST /orders arrives: step 0's catch-up has to persist it, and the sale has to reach both stores.
        jdbc.sql("UPDATE events SET strategy = 'REDIS_FIRST' WHERE id = ?").param(event).update();
        try {
            UUID gold = seats.get(0);
            UUID silver = seats.get(1);
            UUID holdId = hold(alice, gold, silver);
            assertThat(count("SELECT count(*) FROM holds WHERE id = ?", holdId)).as("not in Postgres yet").isZero();

            Response response = postOrder(alice, holdId);

            assertThat(response.status()).as(response.body().toString()).isEqualTo(201);
            UUID orderId = orderId(response);
            assertThat(response.body().path("status").asText()).isEqualTo("PAID");
            assertThat(response.body().path("totalPaise").asLong()).isEqualTo(GOLD_PAISE + SILVER_PAISE);
            // Postgres: sold, converted, paid, tickets issued.
            assertThat(seatStatus(gold)).isEqualTo("SOLD");
            assertThat(seatStatus(silver)).isEqualTo("SOLD");
            assertThat(seatOrder(gold)).isEqualTo(orderId);
            assertThat(holdStatus(holdId)).isEqualTo("CONVERTED");
            assertThat(paymentStatuses(orderId)).containsExactly("SUCCEEDED");
            assertThat(used(aliceId)).as("bought seats keep counting (D-016)").isEqualTo(2);
            assertThat(ticketsOfOrder(orderId)).isEqualTo(2);
            assertThat(sagaState(orderId)).isEqualTo("TICKETS_ISSUED");
            // Redis: the seats are sold for good, the hold is gone, the cap counts the seats forever.
            assertThat(RedisFirstTestAccess.seatValue(context, event, gold)).startsWith("S:" + orderId + ":");
            assertThat(RedisFirstTestAccess.seatValue(context, event, silver)).startsWith("S:" + orderId + ":");
            assertThat(RedisFirstTestAccess.holdExists(context, event, holdId)).isFalse();
            assertThat(RedisFirstTestAccess.boughtSeatsCounted(context, event, aliceId, holdId, 2)).isTrue();
            // And another buyer can't hold a sold seat.
            Response taken = exchange(HttpMethod.POST, "/events/" + event + "/holds",
                    new HttpEntity<>(Map.of("seatIds", List.of(gold)), jsonHeaders(bob)));
            assertThat(taken.status()).isEqualTo(409);
            assertThat(taken.code()).isEqualTo("SEAT_TAKEN");
        } finally {
            RedisFirstTestAccess.deleteEventKeys(context, event);
        }
    }

    @Test
    void boughtSeatsKeepCountingAgainstTheCap() {
        // Limit 4: buy 3, then 2 more free seats are over the cap, 1 more is fine.
        assertThat(postOrder(alice, hold(alice, seats.get(0), seats.get(1), seats.get(2))).status()).isEqualTo(201);

        Response overCap = exchange(HttpMethod.POST, "/events/" + event + "/holds", new HttpEntity<>(
                Map.of("seatIds", List.of(seats.get(3), seats.get(4))), jsonHeaders(alice)));

        assertThat(overCap.status()).isEqualTo(409);
        assertThat(overCap.code()).isEqualTo("LIMIT_EXCEEDED");
        assertThat(used(aliceId)).isEqualTo(3);
        hold(alice, seats.get(3));
        assertThat(used(aliceId)).isEqualTo(4);
    }

    @Test
    void orderingTheSameHoldAgainReturnsTheExistingOrderWith200() {
        UUID holdId = hold(alice, seats.get(0));
        UUID first = orderId(postOrder(alice, holdId));

        Response retry = postOrder(alice, holdId);

        assertThat(retry.status()).isEqualTo(200);
        assertThat(orderId(retry)).isEqualTo(first);
        assertThat(retry.body().path("status").asText()).isEqualTo("PAID");
        assertThat(ordersOfEvent()).isEqualTo(1);
        assertThat(paymentsOfEvent()).as("charged once").isEqualTo(1);
    }

    // --- POST /orders: holds that can't be ordered -----------------------------------------------

    @Test
    void someoneElsesHoldIs404LikeAnUnknownOne() {
        UUID alicesHold = hold(alice, seats.get(0));

        Response bobs = postOrder(bob, alicesHold);
        Response unknown = postOrder(bob, UUID.randomUUID());

        assertThat(bobs.status()).isEqualTo(404);
        assertThat(bobs.code()).isEqualTo("HOLD_NOT_FOUND");
        assertThat(unknown.status()).isEqualTo(404);
        assertThat(unknown.code()).isEqualTo("HOLD_NOT_FOUND");
        assertThat(holdStatus(alicesHold)).isEqualTo("ACTIVE");
        assertThat(seatStatus(seats.get(0))).isEqualTo("HELD");
        assertThat(ordersOfEvent()).isZero();
    }

    @Test
    void anExpiredHoldIs410AndNothingIsSoldOrCharged() {
        // ACTIVE but past expires_at: the sweeper (disabled in these tests) hasn't closed it yet.
        UUID holdId = hold(alice, seats.get(0), seats.get(1));
        jdbc.sql("UPDATE holds SET created_at = now() - interval '6 minutes', expires_at = now() - interval '1 minute' "
                + "WHERE id = ?").param(holdId).update();

        Response response = postOrder(alice, holdId);

        assertThat(response.status()).isEqualTo(410);
        assertThat(response.code()).isEqualTo("HOLD_EXPIRED");
        assertThat(ordersOfEvent()).isZero();
        assertThat(paymentsOfEvent()).isZero();
        assertThat(seatStatus(seats.get(0))).isEqualTo("HELD");
        assertThat(holdStatus(holdId)).isEqualTo("ACTIVE");
    }

    @Test
    void aReleasedHoldIs410() {
        UUID holdId = hold(alice, seats.get(0));
        assertThat(request(HttpMethod.DELETE, "/holds/" + holdId, alice).status()).isEqualTo(204);

        Response response = postOrder(alice, holdId);

        assertThat(response.status()).isEqualTo(410);
        assertThat(response.code()).isEqualTo("HOLD_EXPIRED");
        assertThat(ordersOfEvent()).isZero();
        assertThat(seatStatus(seats.get(0))).isEqualTo("AVAILABLE");
    }

    @Test
    void cancellingAnOrderedHoldIsHoldConverted() {
        UUID holdId = hold(alice, seats.get(0));
        postOrder(alice, holdId);

        Response cancel = request(HttpMethod.DELETE, "/holds/" + holdId, alice);

        assertThat(cancel.status()).isEqualTo(409);
        assertThat(cancel.code()).isEqualTo("HOLD_CONVERTED");
        assertThat(seatStatus(seats.get(0))).isEqualTo("SOLD");
    }

    @Test
    void aSeatStillInAnotherOrdersActiveItemsIs409SeatTaken() {
        // An unfinished checkout of Bob's (crashed, or still in flight on a hold that has ended) keeps
        // an active item on seat 0 although the seat itself is AVAILABLE. Alice can hold the seat, but
        // her order must be refused by uq_order_items_active_seat, with nothing written.
        UUID bobsOldHold = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO holds (id, event_id, user_id, seat_ids, expires_at, created_at, status)
                VALUES (?, ?, ?, ?, now() - interval '1 minute', now() - interval '6 minutes', 'EXPIRED')
                """).params(bobsOldHold, event, bobId, new UUID[] {seats.get(0)}).update();
        UUID bobsOrder = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO orders (id, user_id, event_id, hold_id, status, total_paise, saga_state)
                VALUES (?, ?, ?, ?, 'PENDING', ?, 'ORDER_CREATED')
                """).params(bobsOrder, bobId, event, bobsOldHold, GOLD_PAISE).update();
        jdbc.sql("INSERT INTO order_items (order_id, event_id, seat_id, price_paise) VALUES (?, ?, ?, ?)")
                .params(bobsOrder, event, seats.get(0), GOLD_PAISE).update();
        UUID alicesHold = hold(alice, seats.get(0));

        Response response = postOrder(alice, alicesHold);

        assertThat(response.status()).isEqualTo(409);
        assertThat(response.code()).isEqualTo("SEAT_TAKEN");
        assertThat(uuids(response.body().path("seatIds"))).containsExactly(seats.get(0));
        assertThat(count("SELECT count(*) FROM orders WHERE hold_id = ?", alicesHold)).isZero();
        assertThat(paymentsOfEvent()).isZero();
        assertThat(holdStatus(alicesHold)).isEqualTo("ACTIVE");

        // What M3 recovery would do; lets the after-test invariants run on a settled state.
        jdbc.sql("UPDATE order_items SET active = false WHERE order_id = ?").param(bobsOrder).update();
        jdbc.sql("UPDATE orders SET status = 'CANCELLED' WHERE id = ?").param(bobsOrder).update();
    }

    @Test
    void missingHoldIdIs400AndNoTokenIs401() {
        Response missing = exchange(HttpMethod.POST, "/orders", new HttpEntity<>(Map.of(), jsonHeaders(alice)));
        Response anonymous = postOrder(null, hold(alice, seats.get(0)));

        assertThat(missing.status()).isEqualTo(400);
        assertThat(missing.code()).isEqualTo("VALIDATION_FAILED");
        assertThat(anonymous.status()).isEqualTo(401);
        assertThat(anonymous.code()).isEqualTo("UNAUTHENTICATED");
        assertThat(ordersOfEvent()).isZero();
    }

    // --- GET /orders/{id} ------------------------------------------------------------------------

    @Test
    void theOwnerCanReadTheirOrder() {
        UUID holdId = hold(alice, seats.get(0));
        Response placed = postOrder(alice, holdId);

        Response read = getOrder(alice, orderId(placed));

        assertThat(read.status()).isEqualTo(200);
        assertThat(read.body()).isEqualTo(placed.body());
    }

    @Test
    void someoneElsesOrderIs404LikeAnUnknownOne() {
        UUID orderId = orderId(postOrder(alice, hold(alice, seats.get(0))));

        Response bobs = getOrder(bob, orderId);
        Response unknown = getOrder(alice, UUID.randomUUID());

        assertThat(bobs.status()).isEqualTo(404);
        assertThat(bobs.code()).isEqualTo("ORDER_NOT_FOUND");
        assertThat(unknown.status()).isEqualTo(404);
        assertThat(unknown.code()).isEqualTo("ORDER_NOT_FOUND");
        assertThat(bobs.body().has("status") && bobs.body().has("totalPaise")).as("no order details").isFalse();
    }

    @Test
    void readingAnOrderNeedsAToken() {
        UUID orderId = orderId(postOrder(alice, hold(alice, seats.get(0))));

        assertThat(getOrder(null, orderId).status()).isEqualTo(401);
    }
}
