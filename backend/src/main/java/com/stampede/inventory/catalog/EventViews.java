package com.stampede.inventory.catalog;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Response bodies of GET /events and GET /events/{id}. No availability counts on purpose (D-010):
 * live demand comes from realtime (M5). {@code serverTime} lets the client correct its countdown for
 * a wrong local clock.
 */
final class EventViews {

    private EventViews() {
    }

    /** Statuses a buyer can see. DRAFT is never returned (filtered in SQL). */
    enum EventStatus { SCHEDULED, LIVE, PAUSED, SOLD_OUT, ENDED }

    record VenueRef(UUID id, String name) {}

    record PriceTier(UUID id, String name, long pricePaise) {}

    record EventSummary(
            UUID id, String title, EventStatus status, Instant dropAt, Instant startsAt,
            int perUserLimit, VenueRef venue) {}

    /** Wrapped in an object so a pagination cursor can be added later without breaking clients. */
    record EventList(List<EventSummary> items, Instant serverTime) {}

    record EventDetail(
            UUID id, String title, EventStatus status, Instant dropAt, Instant startsAt,
            int perUserLimit, VenueRef venue, List<PriceTier> tiers, Instant serverTime) {}
}
