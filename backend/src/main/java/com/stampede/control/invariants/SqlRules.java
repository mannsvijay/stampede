package com.stampede.control.invariants;

import java.util.List;

/**
 * The invariants as SQL (M1.7, D-019). Each query returns one row per violation for the event
 * {@code :eventId}, so an empty result means the rule holds.
 * <p>
 * Taken over from the tests: I1-I3 (M1.3) and I8 (M1.4) from {@code HoldInvariants}; O1-O5 (M1.5)
 * and T1-T3 (M1.6) from {@code CheckoutTestSupport}. New in M1.7: V1, O6, O7, C1.
 * <p>
 * PRD 6.10's five invariants: 1 = O6 (and O1), 2 = C1, 3 = T3, 4 = O1, 5 = the Redis rule (M2.2).
 * <p>
 * <b>Grace.</b> The checkout runs one transaction per step (D-017), so between steps a few states are
 * briefly legitimate: seats SOLD and hold CONVERTED while the order is still PENDING (steps 3-4, O1
 * and O5), an order PENDING at all (O7), a PAID order still at SEATS_SOLD (step 5, T3), and a
 * CANCELLED order whose charge is not yet refunded (compensation, O4). With {@code :graceMs > 0}
 * those are excused while the order is younger than the grace period; with 0 (tests) nothing is.
 * {@code orders} has only {@code created_at}, so age is measured from order creation.
 * <p>
 * <b>Table ownership (D-007).</b> These queries read tables owned by inventory, booking, payments
 * and ticketing. The checker is the one recorded exception (D-019): it only reads, in a read-only
 * transaction, because its rules are joins across modules that no single module can check.
 */
final class SqlRules {

    /** True while order {@code alias} is within the grace period; always false when strict. */
    private static String young(String alias) {
        return "coalesce(:graceMs > 0 AND " + alias + ".created_at > now() - interval '1 millisecond' * :graceMs, false)";
    }

    /** I1: a seat is listed by at most one ACTIVE hold. */
    static final SqlCheck I1 = new SqlCheck("I1", "seat in two active holds", """
            SELECT s.seat_id::text || ' listed by active holds ' || array_agg(h.id)::text
            FROM holds h
            CROSS JOIN LATERAL unnest(h.seat_ids) AS s(seat_id)
            WHERE h.event_id = :eventId AND h.status = 'ACTIVE'
            GROUP BY s.seat_id
            HAVING count(*) > 1
            """);

    /** I2: every HELD seat points to a hold that exists, is ACTIVE and lists that seat. */
    static final SqlCheck I2 = new SqlCheck("I2", "held seat without its active hold", """
            SELECT es.seat_id::text || ' is HELD by ' || es.hold_id::text || ' whose status is '
                   || coalesce(h.status, 'MISSING')
                   || CASE WHEN h.id IS NOT NULL AND NOT es.seat_id = ANY (h.seat_ids)
                           THEN ' and which does not list it' ELSE '' END
            FROM event_seats es
            LEFT JOIN holds h ON h.id = es.hold_id
            WHERE es.event_id = :eventId AND es.status = 'HELD'
              AND (h.id IS NULL OR h.status <> 'ACTIVE' OR NOT es.seat_id = ANY (h.seat_ids))
            """);

    /** I3: every seat an ACTIVE hold lists is really HELD by that hold (the other direction is I2). */
    static final SqlCheck I3 = new SqlCheck("I3", "active hold not owning its seat", """
            SELECT 'active hold ' || h.id::text || ' lists ' || s.seat_id::text || ' but the seat is '
                   || coalesce(es.status, 'MISSING') || ' owned by ' || coalesce(es.hold_id::text, 'nobody')
            FROM holds h
            CROSS JOIN LATERAL unnest(h.seat_ids) AS s(seat_id)
            LEFT JOIN event_seats es ON es.event_id = h.event_id AND es.seat_id = s.seat_id
            WHERE h.event_id = :eventId AND h.status = 'ACTIVE'
              AND (es.status IS DISTINCT FROM 'HELD' OR es.hold_id IS DISTINCT FROM h.id)
            """);

    /**
     * I8: each user's allowance counts exactly the seats of their ACTIVE holds plus their bought
     * seats, and never exceeds the event's per_user_limit (D-005). Bought seats are counted as the
     * seats of CONVERTED holds (D-017): a converted hold's seats are exactly its order's seats, and
     * counting holds (not order_items) avoids counting a seat twice while its order is PENDING and its
     * hold still ACTIVE. A user with such holds but no allowance row is a violation; a row at 0
     * without holds is fine. {@code used > per_user_limit} stays strict: a future limit change must
     * refuse to go below any user's current {@code used} (D-019), so it can never be legitimate.
     */
    static final SqlCheck I8 = new SqlCheck("I8", "allowance drift", """
            WITH ev AS (SELECT id, per_user_limit FROM events WHERE id = :eventId),
                 held AS (SELECT h.user_id, sum(cardinality(h.seat_ids)) AS seats
                          FROM holds h JOIN ev ON h.event_id = ev.id
                          WHERE h.status IN ('ACTIVE', 'CONVERTED')
                          GROUP BY h.user_id),
                 counted AS (SELECT a.user_id, a.used
                             FROM user_event_allowance a JOIN ev ON a.event_id = ev.id)
            SELECT 'user ' || coalesce(c.user_id, h.user_id)::text || ' has used = '
                   || coalesce(c.used::text, 'no row') || ' but ' || coalesce(h.seats, 0)
                   || ' seats in active or converted holds (limit ' || ev.per_user_limit || ')'
            FROM counted c
            FULL JOIN held h ON h.user_id = c.user_id
            CROSS JOIN ev
            WHERE c.user_id IS NULL
               OR c.used <> coalesce(h.seats, 0)
               OR c.used > ev.per_user_limit
            """);

    /** V1 (M0.2 note): every event_seat's seat belongs to the event's venue. No FK can say this. */
    static final SqlCheck V1 = new SqlCheck("V1", "event seat outside the event's venue", """
            SELECT 'seat ' || es.seat_id::text || ' is in venue ' || sec.venue_id::text
                   || ' but the event is at ' || e.venue_id::text
            FROM event_seats es
            JOIN events e ON e.id = es.event_id
            JOIN seats s ON s.id = es.seat_id
            JOIN sections sec ON sec.id = s.section_id
            WHERE es.event_id = :eventId AND sec.venue_id <> e.venue_id
            """);

    /**
     * O1 (PRD 6.10 #4, "every sold seat has a paid order"): a SOLD seat has exactly one active item,
     * and it is in the PAID order the seat points to. Grace: seats sold to a PENDING order still in
     * its checkout (step 3 committed, step 4 not yet).
     */
    static final SqlCheck O1 = new SqlCheck("O1", "sold seat without exactly one active item in its PAID order", """
            SELECT es.seat_id::text || ' is SOLD to ' || es.order_id::text || ' with '
                   || count(oi.order_id) FILTER (WHERE oi.active) || ' active items, '
                   || count(oi.order_id) FILTER (WHERE oi.active AND o.status = 'PAID' AND o.id = es.order_id)
                   || ' of them in its PAID order'
            FROM event_seats es
            LEFT JOIN order_items oi ON oi.event_id = es.event_id AND oi.seat_id = es.seat_id
            LEFT JOIN orders o ON o.id = oi.order_id
            WHERE es.event_id = :eventId AND es.status = 'SOLD'
              AND NOT EXISTS (SELECT 1 FROM orders so
                              WHERE so.id = es.order_id AND so.status = 'PENDING' AND %s)
            GROUP BY es.seat_id, es.order_id
            HAVING count(oi.order_id) FILTER (WHERE oi.active) <> 1
                OR count(oi.order_id) FILTER (WHERE oi.active AND o.status = 'PAID' AND o.id = es.order_id) <> 1
            """.formatted(young("so")));

    /** O2: every seat of a PAID order is SOLD to that order, and its item is active. */
    static final SqlCheck O2 = new SqlCheck("O2", "PAID order whose seat isn't SOLD to it", """
            SELECT 'order ' || o.id::text || ' is PAID but seat ' || oi.seat_id::text || ' is ' || es.status
                   || ' owned by ' || coalesce(es.order_id::text, 'no order')
                   || CASE WHEN oi.active THEN '' ELSE ' (item inactive)' END
            FROM orders o
            JOIN order_items oi ON oi.order_id = o.id
            JOIN event_seats es ON es.event_id = oi.event_id AND es.seat_id = oi.seat_id
            WHERE o.event_id = :eventId AND o.status = 'PAID'
              AND (es.status <> 'SOLD' OR es.order_id IS DISTINCT FROM o.id OR NOT oi.active)
            """);

    /** O3: only PENDING and PAID orders have active items (cancelling deactivates them). */
    static final SqlCheck O3 = new SqlCheck("O3", "active item in an order that isn't PENDING or PAID", """
            SELECT 'order ' || o.id::text || ' is ' || o.status || ' but seat ' || oi.seat_id::text
                   || ' is still active'
            FROM orders o JOIN order_items oi ON oi.order_id = o.id
            WHERE o.event_id = :eventId AND oi.active AND o.status NOT IN ('PENDING', 'PAID')
            """);

    /**
     * O4: a PAID order has exactly one SUCCEEDED payment, of its total, and its total is the sum of its
     * items; a CANCELLED order keeps no succeeded charge. Grace: a CANCELLED order whose refund (the
     * compensation's last step, D-017) hasn't committed yet.
     */
    static final SqlCheck O4 = new SqlCheck("O4", "money: PAID needs one SUCCEEDED payment of its total, CANCELLED none", """
            SELECT 'order ' || o.id::text || ' is ' || o.status || ' (total ' || o.total_paise || ', items '
                   || (SELECT coalesce(sum(price_paise), 0) FROM order_items WHERE order_id = o.id)
                   || ') with succeeded payments ' || coalesce(array_agg(p.amount_paise)
                          FILTER (WHERE p.status = 'SUCCEEDED')::text, '{}')
            FROM orders o
            LEFT JOIN payments p ON p.order_id = o.id
            WHERE o.event_id = :eventId
            GROUP BY o.id, o.status, o.total_paise, o.created_at
            HAVING (o.status = 'PAID' AND (count(p.id) FILTER (WHERE p.status = 'SUCCEEDED') <> 1
                        OR min(p.amount_paise) FILTER (WHERE p.status = 'SUCCEEDED') <> o.total_paise
                        OR o.total_paise <> (SELECT coalesce(sum(price_paise), 0) FROM order_items
                                             WHERE order_id = o.id)))
                OR (o.status = 'CANCELLED' AND count(p.id) FILTER (WHERE p.status = 'SUCCEEDED') > 0
                    AND NOT %s)
            """.formatted(young("o")));

    /**
     * O5: a hold is CONVERTED exactly when its order is PAID. Grace: a CONVERTED hold whose order is
     * still PENDING in its checkout (between steps 3 and 4).
     */
    static final SqlCheck O5 = new SqlCheck("O5", "CONVERTED hold without a PAID order, or PAID order whose hold isn't CONVERTED", """
            SELECT 'hold ' || h.id::text || ' is ' || h.status || ', its order is '
                   || coalesce(o.status, 'missing')
            FROM holds h
            FULL JOIN orders o ON o.hold_id = h.id
            WHERE coalesce(h.event_id, o.event_id) = :eventId
              AND ((h.status = 'CONVERTED' AND o.status IS DISTINCT FROM 'PAID'
                        AND NOT (o.status IS NOT DISTINCT FROM 'PENDING' AND %s))
                OR (o.status = 'PAID' AND h.status IS DISTINCT FROM 'CONVERTED'))
            """.formatted(young("o")));

    /**
     * O6 (PRD 6.10 #1, "no seat belongs to more than one active order"). {@code uq_order_items_active_seat}
     * makes this impossible; the rule is a tripwire in case that index is ever missing or bypassed.
     */
    static final SqlCheck O6 = new SqlCheck("O6", "seat in more than one active order", """
            SELECT 'seat ' || oi.seat_id::text || ' is active in orders '
                   || array_agg(oi.order_id ORDER BY oi.order_id)::text
            FROM order_items oi
            WHERE oi.event_id = :eventId AND oi.active
            GROUP BY oi.seat_id
            HAVING count(*) > 1
            """);

    /**
     * O7 (D-017 known limits): a PENDING order older than a checkout takes is stuck (a crash between
     * steps, step 4 failing every attempt, or a compensating release that threw). Its active items
     * keep its seats from every other buyer. Strict mode reports every PENDING order.
     */
    static final SqlCheck O7 = new SqlCheck("O7", "PENDING order older than a checkout takes", """
            SELECT 'order ' || o.id::text || ' is PENDING at saga state ' || o.saga_state || ', created '
                   || round(extract(epoch FROM now() - o.created_at))::text || ' s ago'
            FROM orders o
            WHERE o.event_id = :eventId AND o.status = 'PENDING' AND NOT %s
            """.formatted(young("o")));

    /**
     * C1 (PRD 6.10 #2, "sold seats never exceed capacity"): SOLD seats, active items of PAID orders and
     * live tickets each number at most the venue's seats. With the primary keys, the unique indexes
     * and V1 this can't fail on its own; it is the PRD's headline rule, kept as a tripwire.
     */
    static final SqlCheck C1 = new SqlCheck("C1", "sold seats exceed capacity", """
            WITH cap AS (SELECT count(*) AS seats
                         FROM events e
                         JOIN sections sec ON sec.venue_id = e.venue_id
                         JOIN seats s ON s.section_id = sec.id
                         WHERE e.id = :eventId),
                 sold AS (SELECT count(*) AS n FROM event_seats WHERE event_id = :eventId AND status = 'SOLD'),
                 paid AS (SELECT count(*) AS n
                          FROM order_items oi JOIN orders o ON o.id = oi.order_id
                          WHERE oi.event_id = :eventId AND oi.active AND o.status = 'PAID'),
                 live AS (SELECT count(*) AS n FROM tickets WHERE event_id = :eventId AND status <> 'REVOKED')
            SELECT 'venue capacity ' || cap.seats || ' but ' || sold.n || ' SOLD seats, ' || paid.n
                   || ' active items in PAID orders, ' || live.n || ' live tickets'
            FROM cap, sold, paid, live
            WHERE sold.n > cap.seats OR paid.n > cap.seats OR live.n > cap.seats
            """);

    /** T1: a live ticket's seat is SOLD to the ticket's PAID order, and its holder is the buyer. */
    static final SqlCheck T1 = new SqlCheck("T1", "live ticket whose seat isn't SOLD to its PAID order, or held by someone else", """
            SELECT 'ticket ' || t.id::text || ' for seat ' || t.seat_id::text || ' is in order '
                   || t.order_id::text || ' (' || o.status || '), seat is ' || es.status || ' owned by '
                   || coalesce(es.order_id::text, 'no order')
                   || CASE WHEN t.holder_user_id = o.user_id THEN '' ELSE ', holder is not the buyer' END
            FROM tickets t
            JOIN orders o ON o.id = t.order_id
            JOIN event_seats es ON es.event_id = t.event_id AND es.seat_id = t.seat_id
            WHERE t.event_id = :eventId AND t.status <> 'REVOKED'
              AND (o.status <> 'PAID' OR es.status <> 'SOLD' OR es.order_id IS DISTINCT FROM t.order_id
                   OR t.holder_user_id <> o.user_id)
            """);

    /** T2: an order at TICKETS_ISSUED is PAID and has exactly one live ticket per active item. */
    static final SqlCheck T2 = new SqlCheck("T2", "order at TICKETS_ISSUED without exactly one live ticket per active item", """
            SELECT 'order ' || o.id::text || ' (' || o.status || '/' || o.saga_state || ') has '
                   || (SELECT count(*) FROM order_items oi WHERE oi.order_id = o.id AND oi.active)
                   || ' active items and live tickets for '
                   || (SELECT count(*) FROM order_items oi JOIN tickets t ON t.order_id = o.id
                           AND t.event_id = oi.event_id AND t.seat_id = oi.seat_id AND t.status <> 'REVOKED'
                       WHERE oi.order_id = o.id AND oi.active)
                   || ' of them, ' || (SELECT count(*) FROM tickets t WHERE t.order_id = o.id
                                          AND t.status <> 'REVOKED') || ' in all'
            FROM orders o
            WHERE o.event_id = :eventId AND o.saga_state = 'TICKETS_ISSUED'
              AND (o.status <> 'PAID'
                OR (SELECT count(*) FROM order_items oi WHERE oi.order_id = o.id AND oi.active)
                   <> (SELECT count(*) FROM order_items oi JOIN tickets t ON t.order_id = o.id
                           AND t.event_id = oi.event_id AND t.seat_id = oi.seat_id AND t.status <> 'REVOKED'
                       WHERE oi.order_id = o.id AND oi.active)
                OR (SELECT count(*) FROM tickets t WHERE t.order_id = o.id AND t.status <> 'REVOKED')
                   <> (SELECT count(*) FROM order_items oi WHERE oi.order_id = o.id AND oi.active))
            """);

    /**
     * T3 (PRD 6.10 #3, "every paid order has issued tickets"). Grace: a PAID order whose step 5 is still
     * running. Past it, the order is one of D-018's "PAID without tickets" cases.
     */
    static final SqlCheck T3 = new SqlCheck("T3", "PAID order whose tickets aren't issued", """
            SELECT 'order ' || o.id::text || ' is PAID at saga state ' || o.saga_state || ' with '
                   || (SELECT count(*) FROM tickets t WHERE t.order_id = o.id AND t.status <> 'REVOKED')
                   || ' live tickets'
            FROM orders o
            WHERE o.event_id = :eventId AND o.status = 'PAID' AND o.saga_state <> 'TICKETS_ISSUED'
              AND NOT %s
            """.formatted(young("o")));

    /** Every SQL rule, in report order. */
    static final List<InvariantCheck> ALL = List.of(I1, I2, I3, I8, V1, O1, O2, O3, O4, O5, O6, O7, C1, T1, T2, T3);

    private SqlRules() {
    }
}
