package com.stampede.inventory.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The pessimistic strategy (M1.2, D-015) against the shared contract ({@link InventoryStrategyContractTest}
 * and {@link PostgresLockingContractTest}).
 * Every test there runs here too; the lock tests in it prove the {@code SELECT ... FOR NO KEY UPDATE} order.
 * The tests here race a hold against the inserts whose foreign keys check its seats (order creation,
 * ticket issuing): only this strategy holds explicit seat locks while it decides.
 */
class PessimisticInventoryStrategyTest extends PostgresLockingContractTest {

    @Autowired
    PessimisticInventoryStrategy pessimistic;

    @Override
    protected InventoryStrategy strategyUnderTest() {
        return pessimistic;
    }

    @Test
    void kindIsPessimistic() {
        assertThat(strategy.kind()).isEqualTo(StrategyKind.PESSIMISTIC);
    }

    /**
     * The 40P01 deadlock M2.4a found, step by step. Ticket issuing used to insert in Java's UUID order,
     * which puts SEAT_2 before SEAT_1 (D-015); a hold locks in Postgres order, SEAT_1 first. Each ticket
     * insert checks {@code fk_tickets_event_seat} with {@code FOR KEY SHARE} on its seat. The issuer here
     * keeps that old Java order on purpose, so this proves the lock mode alone breaks the cycle
     * (TicketIssuer now also inserts in Postgres order; TicketIssuingConcurrencyTest checks that).
     * <ol>
     *   <li>The issuer inserts SEAT_2's ticket: KEY SHARE on SEAT_2.</li>
     *   <li>Another hold in flight locks SEAT_2 {@code FOR NO KEY UPDATE}, which doesn't conflict with
     *       KEY SHARE. It keeps the hold under test waiting at a known point.</li>
     *   <li>The hold for both seats locks SEAT_1 and waits for SEAT_2.</li>
     *   <li>The issuer inserts SEAT_1's ticket. With the hold's seat locks {@code FOR UPDATE}, it waits
     *       for the hold.</li>
     *   <li>The other hold ends. With {@code FOR UPDATE}, the hold now waits for the issuer's KEY SHARE on
     *       SEAT_2 while the issuer waits for the hold: a cycle, and Postgres aborts one with 40P01.</li>
     * </ol>
     * With {@code FOR NO KEY UPDATE}, step 4 doesn't wait, the issuer commits, and the hold, once it has
     * SEAT_2, rejects both seats as taken (they are sold).
     */
    @Test
    void aHoldAndTicketIssuingOnTheSameSeatsDoNotDeadlock() throws Exception {
        UUID orderId = paidOrderWithoutTickets(SEAT_1, SEAT_2);
        try (Connection issuer = openTransaction(); Connection otherHold = openTransaction()) {
            insertTicket(issuer, orderId, SEAT_2);
            exec(otherHold, "SELECT 1 FROM event_seats WHERE event_id = ? AND seat_id = ? FOR NO KEY UPDATE",
                    EVENT_A, SEAT_2);

            Future<HoldOutcome> hold = THREADS.submit(
                    () -> strategy.hold(requestBy(OTHER_USER, UUID.randomUUID(), EVENT_A, SEAT_2, SEAT_1)));
            waitUntilOneSessionIsBlockedOnALock();   // the hold: SEAT_1 locked, waiting for SEAT_2

            Future<Void> issuing = THREADS.submit(() -> {
                insertTicket(issuer, orderId, SEAT_1);
                exec(issuer, "UPDATE orders SET saga_state = 'TICKETS_ISSUED' WHERE id = ?", orderId);
                issuer.commit();
                return null;
            });
            waitUntil(() -> issuing.isDone() || sessionsWaitingForALock() == 2,
                    "the issuer finished or waits for the hold");
            boolean issuerWaitedForTheHold = !issuing.isDone();
            otherHold.rollback();

            assertSeatTaken(hold.get(30, TimeUnit.SECONDS), SEAT_1, SEAT_2);
            issuing.get(30, TimeUnit.SECONDS);
            assertThat(issuerWaitedForTheHold).as("a ticket insert waited for the hold's seat lock").isFalse();
        }
        assertThat(jdbc.sql("SELECT count(*) FROM tickets WHERE order_id = ?").param(orderId).query(Long.class).single())
                .isEqualTo(2);
    }

    /**
     * Order creation (checkout step 1, D-017) inserts {@code order_items}, whose foreign key checks each
     * seat with {@code FOR KEY SHARE}, while the buyer's hold still has the seats HELD. Another buyer's
     * hold attempt on them locks them before it sees HELD and gives up. With {@code FOR UPDATE} the order
     * creation waited for that attempt; it could not deadlock, because both go in Postgres seat order
     * ({@code OrderWriter} inserts in the order of {@code HoldService}'s {@code ORDER BY es.seat_id}).
     * With {@code FOR NO KEY UPDATE} it doesn't wait at all. Same setup as the ticket test above.
     */
    @Test
    void anOrderCreationDoesNotWaitForAHoldAttemptOnItsSeats() throws Exception {
        UUID holdId = UUID.randomUUID();
        holdDirectly(holdId, EVENT_A, SEAT_1, SEAT_2);
        try (Connection checkout = openTransaction(); Connection otherHold = openTransaction()) {
            exec(otherHold, "SELECT 1 FROM event_seats WHERE event_id = ? AND seat_id = ? FOR NO KEY UPDATE",
                    EVENT_A, SEAT_2);
            Future<HoldOutcome> attempt = THREADS.submit(
                    () -> strategy.hold(requestBy(OTHER_USER, UUID.randomUUID(), EVENT_A, SEAT_1, SEAT_2)));
            waitUntilOneSessionIsBlockedOnALock();   // the attempt: SEAT_1 locked, waiting for SEAT_2

            // Step 1 as OrderWriter does it: the order row, then its items in Postgres seat order.
            Future<Void> creation = THREADS.submit(() -> {
                UUID orderId = UUID.randomUUID();
                exec(checkout, """
                        INSERT INTO orders (id, user_id, event_id, hold_id, status, total_paise, saga_state)
                        VALUES (?, ?, ?, ?, 'PENDING', 300000, 'ORDER_CREATED')
                        """, orderId, USER, EVENT_A, holdId);
                for (UUID seat : List.of(SEAT_1, SEAT_2)) {
                    exec(checkout, "INSERT INTO order_items (order_id, event_id, seat_id, price_paise, active) "
                            + "VALUES (?, ?, ?, 150000, true)", orderId, EVENT_A, seat);
                }
                return null;
            });
            waitUntil(() -> creation.isDone() || sessionsWaitingForALock() == 2,
                    "the order creation finished or waits for the attempt");
            boolean creationWaitedForTheAttempt = !creation.isDone();
            otherHold.rollback();

            assertSeatTaken(attempt.get(30, TimeUnit.SECONDS), SEAT_1, SEAT_2);
            creation.get(30, TimeUnit.SECONDS);
            // Rolled back: a PENDING order nobody finishes would fail the strict after-test invariants.
            checkout.rollback();
            assertThat(creationWaitedForTheAttempt).as("an order_items insert waited for a hold attempt's seat lock")
                    .isFalse();
        }
    }

    /**
     * Test setup without the strategy: one PAID order of USER at saga state SEATS_SOLD (step 5 not run
     * yet) owning these seats, with its CONVERTED hold, active items, SUCCEEDED payment and allowance,
     * and no tickets. Once a test inserts its tickets and records TICKETS_ISSUED, the strict after-test
     * invariant check sees a finished checkout.
     */
    private UUID paidOrderWithoutTickets(UUID... seats) {
        UUID holdId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO holds (id, event_id, user_id, seat_ids, expires_at, status)
                VALUES (?, ?, ?, ?, now() + interval '5 minutes', 'CONVERTED')
                """).params(holdId, EVENT_A, USER, seats).update();
        UUID orderId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO orders (id, user_id, event_id, hold_id, status, total_paise, saga_state)
                VALUES (?, ?, ?, ?, 'PAID', ?, 'SEATS_SOLD')
                """).params(orderId, USER, EVENT_A, holdId, 150000L * seats.length).update();
        for (UUID seat : seats) {
            jdbc.sql("""
                    UPDATE event_seats SET status = 'SOLD', order_id = ?, version = version + 1
                    WHERE event_id = ? AND seat_id = ?
                    """).params(orderId, EVENT_A, seat).update();
            jdbc.sql("INSERT INTO order_items (order_id, event_id, seat_id, price_paise) VALUES (?, ?, ?, 150000)")
                    .params(orderId, EVENT_A, seat).update();
        }
        jdbc.sql("INSERT INTO payments (order_id, status, amount_paise) VALUES (?, 'SUCCEEDED', ?)")
                .params(orderId, 150000L * seats.length).update();
        jdbc.sql("INSERT INTO user_event_allowance (event_id, user_id, used) VALUES (?, ?, ?)")
                .params(EVENT_A, USER, seats.length).update();
        return orderId;
    }

    /** The insert TicketIssuer makes (its foreign key checks the seat with FOR KEY SHARE). */
    private static void insertTicket(Connection connection, UUID orderId, UUID seat) throws SQLException {
        exec(connection, """
                INSERT INTO tickets (order_id, event_id, seat_id, holder_user_id, signature, status, key_id,
                                     section_code, row_label, seat_number)
                VALUES (?, ?, ?, ?, decode(repeat('00', 64), 'hex'), 'ISSUED', '0123abcd', 'A', 'A', 1)
                """, orderId, EVENT_A, seat, USER);
    }
}
