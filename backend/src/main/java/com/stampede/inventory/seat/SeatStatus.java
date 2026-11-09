package com.stampede.inventory.seat;

/**
 * The state of one seat for one event, as stored in {@code event_seats.status} (D-003). Checked-in and
 * transferred are ticket states, not seat states.
 */
public enum SeatStatus {
    /** Nobody owns the seat: {@code hold_id} and {@code order_id} are both NULL. */
    AVAILABLE,
    /** A hold owns the seat: {@code hold_id} is set. */
    HELD,
    /** An order owns the seat: {@code order_id} is set. Final; no transition leaves SOLD (D-014). */
    SOLD
}
