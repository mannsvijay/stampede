-- M1.5 (D-017): an order records the hold it was created from.
--
-- The saga's HoldVerified step (PRD 6.4) needs this pointer, and UNIQUE (hold_id) makes "one hold,
-- at most one order, ever" a database rule: when two POST /orders race for the same hold, the second
-- insert waits for the first transaction and then fails with a unique violation. A hold whose order
-- is cancelled is never reused (every hold attempt gets a new id, D-015), so the constraint doesn't
-- need to exclude cancelled orders.
--
-- NOT NULL without a default is safe: no code path created orders before this migration.

ALTER TABLE orders ADD COLUMN hold_id uuid NOT NULL;

-- Composite FK, like event_seats: the hold must belong to the same event as the order.
ALTER TABLE orders
    ADD CONSTRAINT fk_orders_hold FOREIGN KEY (hold_id, event_id) REFERENCES holds (id, event_id);

ALTER TABLE orders ADD CONSTRAINT uq_orders_hold UNIQUE (hold_id);
