package com.stampede.booking.checkout;

import com.stampede.ticketing.api.IssueTickets;
import com.stampede.ticketing.api.TicketIssuer;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Checkout step 5, TicketsIssued (D-018), as <b>one transaction owned by booking</b>:
 * <ol>
 *   <li>Lock the order row ({@code FOR NO KEY UPDATE}) and require PAID at saga state SEATS_SOLD.
 *       Already TICKETS_ISSUED means an earlier attempt or a concurrent retry finished: nothing to do.</li>
 *   <li>Require the seats to be exactly the order's active items. A PAID order's active items are the
 *       seats SOLD to it (invariants O1, O2), so this is "only seats sold to this order get tickets",
 *       checked on booking's own table (D-007: booking can't read {@code event_seats}).</li>
 *   <li>Issue through {@code ticketing::api}. Its {@code @Transactional} joins this transaction.</li>
 *   <li>Record saga state TICKETS_ISSUED.</li>
 * </ol>
 * Because the check, the tickets and the saga state commit together under the order lock, nothing can
 * change the order between "is it still PAID?" and "issue" (a future refund in M3 would take the same
 * row lock and wait), and TICKETS_ISSUED never exists without its tickets.
 * <p>
 * {@code FOR NO KEY UPDATE}, not {@code FOR UPDATE}: we only change a non-key column, and this lock
 * does not block the {@code KEY SHARE} locks that foreign-key checks take on the order row (the
 * ticket inserts themselves check {@code fk_tickets_order}). It still queues every other writer of
 * this order row, so concurrent step 5 attempts for one order run one at a time.
 * <p>
 * A separate bean from {@link CheckoutService} so the {@code @Transactional} proxy applies (see
 * {@link OrderWriter}).
 */
@Component
class TicketStep {

    private final JdbcClient jdbc;
    private final TicketIssuer tickets;

    TicketStep(JdbcClient jdbc, TicketIssuer tickets) {
        this.jdbc = jdbc;
        this.tickets = tickets;
    }

    /**
     * @throws IllegalStateException if the order isn't PAID at SEATS_SOLD (or TICKETS_ISSUED), if the
     *                               seats aren't exactly its active items, or if ticketing refuses;
     *                               nothing is written then
     */
    @Transactional
    void issue(IssueTickets command) {
        UUID orderId = command.orderId();
        String state = jdbc.sql("SELECT status || '/' || saga_state FROM orders WHERE id = ? FOR NO KEY UPDATE")
                .param(orderId)
                .query(String.class)
                .optional()
                .orElseThrow(() -> new IllegalStateException("No order " + orderId));
        if (state.equals("PAID/TICKETS_ISSUED")) {
            return;
        }
        if (!state.equals("PAID/SEATS_SOLD")) {
            throw new IllegalStateException("Order " + orderId + " is " + state + ", cannot issue tickets");
        }

        List<UUID> items = jdbc.sql("SELECT seat_id FROM order_items WHERE order_id = ? AND active")
                .param(orderId)
                .query(UUID.class)
                .list();
        Set<UUID> requested = new HashSet<>(command.seats().stream().map(IssueTickets.Seat::seatId).toList());
        if (!requested.equals(new HashSet<>(items))) {
            throw new IllegalStateException("Order " + orderId + ": seats " + requested
                    + " are not exactly its active items " + items);
        }

        tickets.issueForOrder(command);

        int updated = jdbc.sql("""
                        UPDATE orders SET saga_state = 'TICKETS_ISSUED'
                        WHERE id = ? AND status = 'PAID' AND saga_state = 'SEATS_SOLD'
                        """)
                .param(orderId)
                .update();
        if (updated != 1) {
            // Impossible while we hold the row lock; fail loudly (rolling back the tickets) if it ever happens.
            throw new IllegalStateException("Order " + orderId + " changed under its lock");
        }
    }
}
