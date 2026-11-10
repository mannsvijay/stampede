package com.stampede.booking.checkout;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Reads of booking's own tables. Every lookup is scoped to the caller, so no order leaks (no IDOR). */
@Component
class OrderQueries {

    private final JdbcClient jdbc;

    OrderQueries(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The order, if it exists and belongs to this user. */
    Optional<OrderView> forUser(UUID userId, UUID orderId) {
        return find("o.id = :key", userId, orderId);
    }

    /** The user's order created from this hold, if any (at most one: {@code uq_orders_hold}). */
    Optional<OrderView> byHold(UUID userId, UUID holdId) {
        return find("o.hold_id = :key", userId, holdId);
    }

    /**
     * Whether this order is PAID but its tickets aren't recorded as issued yet (saga still at
     * SEATS_SOLD): step 5 failed earlier, or is running right now in another request (D-018).
     */
    boolean awaitsTickets(UUID orderId) {
        return jdbc.sql("SELECT status = 'PAID' AND saga_state = 'SEATS_SOLD' FROM orders WHERE id = ?")
                .param(orderId)
                .query(Boolean.class)
                .optional()
                .orElse(false);
    }

    private Optional<OrderView> find(String condition, UUID userId, UUID key) {
        Optional<OrderRow> order = jdbc.sql("""
                        SELECT o.id, o.event_id, o.hold_id, o.status, o.total_paise, o.created_at
                        FROM orders o
                        WHERE o.user_id = :userId AND\s""" + condition)
                .param("userId", userId)
                .param("key", key)
                .query((rs, n) -> new OrderRow(rs.getObject("id", UUID.class), rs.getObject("event_id", UUID.class),
                        rs.getObject("hold_id", UUID.class), OrderStatus.valueOf(rs.getString("status")),
                        rs.getLong("total_paise"), rs.getObject("created_at", OffsetDateTime.class)))
                .optional();
        return order.map(row -> new OrderView(row.id(), row.eventId(), row.holdId(), row.status(),
                row.totalPaise(), items(row.id()), row.createdAt().toInstant()));
    }

    private List<OrderView.Item> items(UUID orderId) {
        return jdbc.sql("SELECT seat_id, price_paise FROM order_items WHERE order_id = ? ORDER BY seat_id")
                .param(orderId)
                .query((rs, n) -> new OrderView.Item(rs.getObject("seat_id", UUID.class), rs.getLong("price_paise")))
                .list();
    }

    private record OrderRow(UUID id, UUID eventId, UUID holdId, OrderStatus status, long totalPaise,
            OffsetDateTime createdAt) {
    }
}
