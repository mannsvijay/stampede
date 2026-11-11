package com.stampede.inventory.catalog;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.stampede.inventory.catalog.SeatMapViews.EventLayout;
import com.stampede.inventory.catalog.SeatMapViews.SeatMap;
import com.stampede.shared.error.ApiException;
import com.stampede.shared.error.ErrorCode;
import com.stampede.shared.error.ErrorResponses;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * GET /events/{id}/layout and GET /events/{id}/seatmap (PRD 8.4, D-020). Both public: SecurityConfig
 * permits exactly these two paths. A malformed id fails UUID conversion before either runs: 400
 * VALIDATION_FAILED. An unknown or DRAFT event is 404 EVENT_NOT_FOUND, as for GET /events/{id}.
 */
@Tag(name = "events")
@RestController
class SeatMapController {

    private final SeatMapQueries queries;

    SeatMapController(SeatMapQueries queries) {
        this.queries = queries;
    }

    @GetMapping("/events/{id}/layout")
    @Operation(summary = "The event's static seat layout",
            description = "Venue outline, stage, sections and every seat with its position and tier. It doesn't "
                    + "change after the event is created, so load it once. " + SeatMapViews.SEAT_ORDER)
    @ErrorResponses({ErrorCode.VALIDATION_FAILED, ErrorCode.EVENT_NOT_FOUND})
    EventLayout eventLayout(@PathVariable UUID id) {
        return queries.layout(id).orElseThrow(() -> notFound(id));
    }

    @GetMapping("/events/{id}/seatmap")
    @Operation(summary = "Which seats are available now",
            description = "One bit per seat, in the order of the layout's seat list. Poll it again after a "
                    + "rejected hold; realtime updates with a sequence number arrive in M5. "
                    + SeatMapViews.BITSET_FORMAT)
    @ErrorResponses({ErrorCode.VALIDATION_FAILED, ErrorCode.EVENT_NOT_FOUND})
    SeatMap seatMap(@PathVariable UUID id) {
        return queries.seatMap(id).orElseThrow(() -> notFound(id));
    }

    private static ApiException notFound(UUID id) {
        return new ApiException(ErrorCode.EVENT_NOT_FOUND, "No event with id " + id + ".");
    }
}
