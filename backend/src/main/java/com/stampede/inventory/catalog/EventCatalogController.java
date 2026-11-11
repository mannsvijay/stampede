package com.stampede.inventory.catalog;

import java.time.Instant;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.stampede.inventory.catalog.EventViews.EventDetail;
import com.stampede.inventory.catalog.EventViews.EventList;
import com.stampede.shared.error.ApiException;
import com.stampede.shared.error.ErrorCode;
import com.stampede.shared.error.ErrorResponses;

import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * GET /events (the departures board, PRD 8.1) and GET /events/{id} (the event page, PRD 8.2).
 * Public: SecurityConfig permits both without a token. The seat map is in {@link SeatMapController}.
 */
@Tag(name = "events")
@RestController
class EventCatalogController {

    /** Hard cap until the list is paginated (D-010). */
    static final int MAX_EVENTS = 100;

    private final EventCatalogQueries queries;

    EventCatalogController(EventCatalogQueries queries) {
        this.queries = queries;
    }

    @GetMapping("/events")
    EventList listEvents() {
        return new EventList(queries.upcoming(MAX_EVENTS), Instant.now());
    }

    /** A malformed id fails UUID conversion before this runs: 400 VALIDATION_FAILED. */
    @GetMapping("/events/{id}")
    @ErrorResponses({ErrorCode.VALIDATION_FAILED, ErrorCode.EVENT_NOT_FOUND})
    EventDetail getEvent(@PathVariable UUID id) {
        return queries.find(id, Instant.now())
                .orElseThrow(() -> new ApiException(ErrorCode.EVENT_NOT_FOUND, "No event with id " + id + "."));
    }
}
