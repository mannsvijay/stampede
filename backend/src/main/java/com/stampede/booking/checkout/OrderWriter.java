package com.stampede.booking.checkout;

import com.stampede.inventory.api.CheckoutHold;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The checkout's writes to booking's own tables (orders, order_items), each one short transaction.
 * <p>
 * A separate bean from {@link CheckoutService} on purpose: {@code @Transactional} works through a
 * Spring proxy around the bean, and a call from one method to another inside the same class skips
 * that proxy, so no transaction would start. Calling these through another bean makes each one real.
 * None of them calls another module, so no strategy ever joins these transactions (D-015).
 */
@Component
class OrderWriter {

    private final JdbcClient jdbc;

    OrderWriter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Step 1, OrderCreated: the PENDING order and its active items, in one transaction. Two requests
     * for the same hold race here: {@code uq_orders_hold} lets one commit and fails the other with a
     * DuplicateKeyException. {@code uq_order_items_active_seat} would stop a second active order for
     * the same seat even if that somehow didn't.
     */
    @Transactional
    void createPending(UUID orderId, UUID userId, CheckoutHold.Orderable hold) {
        jdbc.sql("""
                        INSERT INTO orders (id, user_id, event_id, hold_id, status, total_paise, saga_state)
                        VALUES (:id, :userId, :eventId, :holdId, 'PENDING', :total, 'ORDER_CREATED')
                        """)
                .param("id", orderId)
                .param("userId", userId)
                .param("eventId", hold.eventId())
                .param("holdId", hold.holdId())
                .param("total", hold.totalPaise())
                .update();
        for (CheckoutHold.SeatPrice seat : hold.seats()) {
            jdbc.sql("""
                            INSERT INTO order_items (order_id, event_id, seat_id, price_paise, active)
                            VALUES (:orderId, :eventId, :seatId, :price, true)
                            """)
                    .param("orderId", orderId)
                    .param("eventId", hold.eventId())
                    .param("seatId", seat.seatId())
                    .param("price", seat.pricePaise())
                    .update();
        }
    }

    /**
     * Step 4: PENDING to PAID. Idempotent so the checkout can retry it: if an earlier attempt committed
     * and only its answer got lost, the order is already PAID and this returns without writing.
     */
    @Transactional
    void markPaid(UUID orderId) {
        move(orderId, OrderStatus.PENDING, OrderStatus.PAID, "SEATS_SOLD");
    }

    /**
     * Compensation: PENDING to CANCELLED, and its items inactive, so the unique index on active
     * order_items no longer blocks the seats from being ordered again. Idempotent like markPaid.
     */
    @Transactional
    void cancel(UUID orderId) {
        move(orderId, OrderStatus.PENDING, OrderStatus.CANCELLED, "ORDER_CANCELLED");
        jdbc.sql("UPDATE order_items SET active = false WHERE order_id = ? AND active")
                .param(orderId)
                .update();
    }

    /**
     * One guarded status change. Zero rows updated means the order wasn't in {@code from}: if it is
     * already in {@code to}, an earlier attempt did the work; anything else is an invalid transition.
     */
    private void move(UUID orderId, OrderStatus from, OrderStatus to, String sagaState) {
        if (!from.canMoveTo(to)) {
            throw new IllegalStateException("Order " + orderId + " cannot move from " + from + " to " + to);
        }
        int updated = jdbc.sql("""
                        UPDATE orders SET status = :to, saga_state = :saga
                        WHERE id = :id AND status = :from
                        """)
                .param("to", to.name())
                .param("saga", sagaState)
                .param("id", orderId)
                .param("from", from.name())
                .update();
        if (updated == 1) {
            return;
        }
        OrderStatus current = jdbc.sql("SELECT status FROM orders WHERE id = ?")
                .param(orderId)
                .query((rs, n) -> OrderStatus.valueOf(rs.getString("status")))
                .optional()
                .orElseThrow(() -> new IllegalStateException("No order " + orderId));
        if (current != to) {
            throw new IllegalStateException("Order " + orderId + " is " + current + ", cannot move to " + to);
        }
    }
}
