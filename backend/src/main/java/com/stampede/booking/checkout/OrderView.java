package com.stampede.booking.checkout;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** An order as its owner sees it. */
record OrderView(UUID orderId, UUID eventId, UUID holdId, OrderStatus status, long totalPaise,
        List<Item> items, Instant createdAt) {

    record Item(UUID seatId, long pricePaise) {
    }

    OrderView {
        items = List.copyOf(items);
    }
}
