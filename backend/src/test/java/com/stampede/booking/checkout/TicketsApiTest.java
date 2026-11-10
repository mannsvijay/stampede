package com.stampede.booking.checkout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.stampede.ticketing.api.IssueTickets;
import com.stampede.ticketing.api.TicketIssuer;
import com.stampede.ticketing.signing.TicketPayload;
import com.stampede.ticketing.signing.TicketVerifier;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Tickets from end to end (M1.6, D-018): the checkout's TicketsIssued step, idempotent issuing,
 * GET /me/tickets, and offline verification with nothing but GET /tickets/keys. Lives with the
 * checkout tests because a ticket only exists after a real sale (it reuses their fixture).
 */
class TicketsApiTest extends CheckoutTestSupport {

    @Autowired
    TicketIssuer issuer;

    @Test
    void aSaleIssuesExactlyOneTicketPerSoldSeat() {
        Response response = postOrder(alice, hold(alice, seats.get(0), seats.get(1), seats.get(2)));

        assertThat(response.status()).isEqualTo(201);
        UUID orderId = orderId(response);
        assertThat(sagaState(orderId)).isEqualTo("TICKETS_ISSUED");
        assertThat(ticketsOfOrder(orderId)).isEqualTo(3);
        assertThat(jdbc.sql("SELECT seat_id FROM tickets WHERE order_id = ?").param(orderId).query(UUID.class).list())
                .containsExactlyInAnyOrder(seats.get(0), seats.get(1), seats.get(2));
        assertInvariants();
    }

    @Test
    void issuingTheSameOrderAgainCreatesNoDuplicates() {
        UUID orderId = orderId(postOrder(alice, hold(alice, seats.get(0), seats.get(1))));
        List<UUID> before = ticketIds(orderId);

        issuer.issueForOrder(command(orderId, aliceId, seats.get(0), seats.get(1)));
        issuer.issueForOrder(command(orderId, aliceId, seats.get(0), seats.get(1)));

        assertThat(ticketIds(orderId)).as("the same tickets, nothing new").isEqualTo(before).hasSize(2);
        assertInvariants();
    }

    @Test
    void aSeatWithAnotherOrdersTicketIsRefusedAndNothingIsWritten() {
        UUID aliceOrder = orderId(postOrder(alice, hold(alice, seats.get(0))));
        UUID bobOrder = orderId(postOrder(bob, hold(bob, seats.get(1))));
        long before = ticketsOfEvent();

        // Bob's order "issuing" Alice's seat plus a free seat: the conflict must roll back both.
        assertThatThrownBy(() -> issuer.issueForOrder(command(bobOrder, bobId, seats.get(0), seats.get(5))))
                .hasMessageContaining("already have a live ticket from another order");

        assertThat(ticketsOfEvent()).isEqualTo(before);
        assertThat(jdbc.sql("SELECT order_id FROM tickets WHERE event_id = ? AND seat_id = ?")
                .params(event, seats.get(0)).query(UUID.class).single()).isEqualTo(aliceOrder);
        assertInvariants();
    }

    @Autowired
    TicketStep step;

    @Test
    void stepFiveIssuesOnlyTheOrdersOwnSeats() {
        UUID orderId = orderId(postOrder(alice, hold(alice, seats.get(0))));
        forgetTickets(orderId);

        // seats.get(5) was never sold to this order: rejected, and nothing written (not even seat 0's).
        assertThatThrownBy(() -> step.issue(command(orderId, aliceId, seats.get(0), seats.get(5))))
                .hasMessageContaining("not exactly its active items");
        assertThatThrownBy(() -> step.issue(command(orderId, aliceId)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(ticketsOfOrder(orderId)).isZero();
        assertThat(sagaState(orderId)).isEqualTo("SEATS_SOLD");

        step.issue(command(orderId, aliceId, seats.get(0)));
        assertThat(ticketsOfOrder(orderId)).isEqualTo(1);
        assertInvariants();
    }

    @Test
    void stepFiveIssuesNothingForAnOrderThatIsNoLongerPaid() {
        UUID orderId = orderId(postOrder(alice, hold(alice, seats.get(0))));
        forgetTickets(orderId);
        // What an M3 refund would leave behind; nothing moves a PAID order today.
        jdbc.sql("UPDATE orders SET status = 'REFUNDED', saga_state = 'REFUNDED' WHERE id = ?").param(orderId).update();

        assertThatThrownBy(() -> step.issue(command(orderId, aliceId, seats.get(0))))
                .hasMessageContaining("is REFUNDED/REFUNDED, cannot issue tickets");
        assertThat(ticketsOfOrder(orderId)).isZero();
    }

    @Test
    void stepFiveOnAnOrderWhoseTicketsAreIssuedDoesNothing() {
        UUID orderId = orderId(postOrder(alice, hold(alice, seats.get(0))));
        List<UUID> before = ticketIds(orderId);

        step.issue(command(orderId, aliceId, seats.get(0)));

        assertThat(ticketIds(orderId)).isEqualTo(before);
        assertThat(sagaState(orderId)).isEqualTo("TICKETS_ISSUED");
    }

    @Test
    void myTicketsShowsOnlyTheCallersTicketsWithTheirSeatLabels() {
        UUID aliceOrder = orderId(postOrder(alice, hold(alice, seats.get(2), seats.get(3))));
        UUID bobOrder = orderId(postOrder(bob, hold(bob, seats.get(4))));

        JsonNode aliceTickets = myTickets(alice);
        JsonNode bobTickets = myTickets(bob);

        assertThat(aliceTickets).hasSize(2);
        assertThat(aliceTickets).allSatisfy(t -> assertThat(t.path("orderId").asText()).isEqualTo(aliceOrder.toString()));
        assertThat(field(aliceTickets, "seatId")).containsExactlyInAnyOrder(seats.get(2).toString(), seats.get(3).toString());
        // The fixture's seats are section A, row A, numbered 1..24 in list order.
        assertThat(aliceTickets).allSatisfy(t -> {
            assertThat(t.path("section").asText()).isEqualTo("A");
            assertThat(t.path("row").asText()).isEqualTo("A");
            assertThat(t.path("status").asText()).isEqualTo("ISSUED");
            assertThat(t.path("version").asInt()).isEqualTo(1);
            assertThat(t.path("eventId").asText()).isEqualTo(event.toString());
        });
        assertThat(field(aliceTickets, "seatNumber")).containsExactlyInAnyOrder("3", "4");

        assertThat(bobTickets).hasSize(1);
        assertThat(bobTickets.get(0).path("orderId").asText()).isEqualTo(bobOrder.toString());
        assertThat(bobTickets.get(0).path("seatNumber").asInt()).isEqualTo(5);
    }

    @Test
    void myTicketsNeedsALogin() {
        Response response = get("/me/tickets", null);

        assertThat(response.status()).isEqualTo(401);
        assertThat(response.code()).isEqualTo("UNAUTHENTICATED");
    }

    @Test
    void aWalletQrVerifiesOfflineWithOnlyThePublishedKey() {
        postOrder(alice, hold(alice, seats.get(6)));
        JsonNode ticket = myTickets(alice).get(0);

        // A scanner: download the key set once (no login), then verify with nothing else.
        Response keys = get("/tickets/keys", null);
        assertThat(keys.status()).isEqualTo(200);
        JsonNode jwk = keys.body().path("keys").get(0);
        assertThat(jwk.path("kty").asText()).isEqualTo("OKP");
        assertThat(jwk.path("crv").asText()).isEqualTo("Ed25519");
        assertThat(jwk.path("kid").asText()).as("tests sign with the test key of config/application.properties")
                .isEqualTo("16ea0a37");
        TicketVerifier scanner = new TicketVerifier(Map.of(jwk.path("kid").asText(),
                Base64.getUrlDecoder().decode(jwk.path("x").asText())));

        TicketPayload verified = scanner.verify(ticket.path("qr").asText()).orElseThrow();

        assertThat(verified.ticketId().toString()).isEqualTo(ticket.path("ticketId").asText());
        assertThat(verified.eventId()).isEqualTo(event);
        assertThat(verified.seatId()).isEqualTo(seats.get(6));
        assertThat(verified.holderUserId()).as("holder is the buyer's user id").isEqualTo(aliceId);
        assertThat(verified.version()).isEqualTo(1);
        assertThat(verified.issuedAt().toString()).isEqualTo(ticket.path("issuedAt").asText());
    }

    // --- helpers ---------------------------------------------------------------------------------

    private JsonNode myTickets(String token) {
        Response response = get("/me/tickets", token);
        assertThat(response.status()).as(String.valueOf(response.body())).isEqualTo(200);
        return response.body().path("items");
    }

    private static Set<String> field(JsonNode items, String name) {
        return StreamSupport.stream(items.spliterator(), false).map(t -> t.path(name).asText())
                .collect(Collectors.toSet());
    }

    /** As if step 5 had never succeeded: no tickets, saga back at SEATS_SOLD. */
    private void forgetTickets(UUID orderId) {
        jdbc.sql("DELETE FROM tickets WHERE order_id = ?").param(orderId).update();
        jdbc.sql("UPDATE orders SET saga_state = 'SEATS_SOLD' WHERE id = ?").param(orderId).update();
    }

    private List<UUID> ticketIds(UUID orderId) {
        return jdbc.sql("SELECT id FROM tickets WHERE order_id = ? ORDER BY id").param(orderId).query(UUID.class).list();
    }

    /** Seat labels as the fixture inserts them: section A, row A, number = list position + 1. */
    IssueTickets command(UUID orderId, UUID holder, UUID... seatIds) {
        return new IssueTickets(orderId, event, holder, java.util.Arrays.stream(seatIds)
                .map(seatId -> new IssueTickets.Seat(seatId, "A", "A", seats.indexOf(seatId) + 1))
                .toList());
    }
}
