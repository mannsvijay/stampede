package com.stampede.inventory.holds;

import com.stampede.inventory.api.CancelResult;
import com.stampede.inventory.api.HoldResult;
import com.stampede.inventory.api.HoldResult.HoldView;
import com.stampede.inventory.api.HoldService;
import com.stampede.shared.error.ApiException;
import com.stampede.shared.error.ErrorCode;
import com.stampede.shared.error.ErrorResponses;
import com.stampede.shared.security.CurrentUser;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * POST /events/{id}/holds and DELETE /holds/{id} (PRD 10, D-016). Both need an access token: they
 * are not in SecurityConfig's public list, so the deny-by-default rule answers 401 without one.
 * <p>
 * Admission (M4.2): the hold endpoint will also require an {@code X-Admission-Token} header, checked
 * here through waitingroom's api before {@link HoldService} runs (D-007 lets inventory call it);
 * missing or invalid means 403 NOT_ADMITTED. It isn't in the spec until it is enforced.
 */
@Tag(name = "holds")
@RestController
class HoldController {

    /** Upper bound on one request, so the seat list stays a small IN list; the per-user cap is the real limit. */
    static final int MAX_SEATS_PER_REQUEST = 20;

    record HoldSeatsRequest(@NotNull @Size(min = 1, max = MAX_SEATS_PER_REQUEST) List<@NotNull UUID> seatIds) {
    }

    /**
     * @param serverTime the server's clock when it answered, so the client can correct its countdown to
     *                   {@code expiresAt} for its own clock skew (as GET /events does)
     */
    record HoldResponse(UUID holdId, UUID eventId, List<UUID> seatIds, Instant expiresAt, Instant serverTime) {
    }

    private final HoldService holds;

    HoldController(HoldService holds) {
        this.holds = holds;
    }

    /**
     * 201 with a Location header for a new hold; 200 with the same body when the caller already holds
     * exactly these seats (a retried request, D-016). Unknown seat ids are a 400, like any other
     * invalid request field.
     */
    @PostMapping("/events/{id}/holds")
    @ApiResponse(responseCode = "201", description = "Held; Location is /holds/{holdId}",
            content = @Content(schema = @Schema(implementation = HoldResponse.class)))
    @ApiResponse(responseCode = "200", description = "You already hold exactly these seats; that hold is returned",
            content = @Content(schema = @Schema(implementation = HoldResponse.class)))
    @ErrorResponses({ErrorCode.VALIDATION_FAILED, ErrorCode.UNAUTHENTICATED, ErrorCode.EVENT_NOT_FOUND,
            ErrorCode.SEAT_TAKEN, ErrorCode.LIMIT_EXCEEDED, ErrorCode.SALE_NOT_OPEN})
    ResponseEntity<HoldResponse> hold(@PathVariable UUID id, @Valid @RequestBody HoldSeatsRequest request,
                                      @AuthenticationPrincipal CurrentUser user) {
        if (new HashSet<>(request.seatIds()).size() != request.seatIds().size()) {
            throw invalidSeatIds("must not contain the same seat twice");
        }
        return switch (holds.hold(user.userId(), id, request.seatIds())) {
            case HoldResult.Created created -> ResponseEntity
                    .created(URI.create("/holds/" + created.hold().holdId()))
                    .body(response(created.hold()));
            case HoldResult.Existing existing -> ResponseEntity.ok(response(existing.hold()));
            case HoldResult.EventNotFound notFound ->
                    throw new ApiException(ErrorCode.EVENT_NOT_FOUND, "No event with id " + id + ".");
            case HoldResult.SaleNotOpen notOpen ->
                    throw new ApiException(ErrorCode.SALE_NOT_OPEN, "Seats for this event are not on sale now.");
            case HoldResult.UnknownSeats unknown ->
                    throw invalidSeatIds("contains seats that are not part of this event: " + unknown.seatIds());
            case HoldResult.SeatTaken taken -> throw new ApiException(ErrorCode.SEAT_TAKEN,
                    "Some of these seats were just taken by someone else.",
                    Map.of("seatIds", taken.seatIds(), "takenAgoMs", taken.takenAgoMs()));
            case HoldResult.LimitExceeded limit -> throw new ApiException(ErrorCode.LIMIT_EXCEEDED,
                    "You can hold or buy at most " + limit.limit() + " tickets for this event.",
                    Map.of("seatIds", limit.seatIds(), "limit", limit.limit()));
        };
    }

    /**
     * 204 when the hold is released, and also when it had already been released or had expired: the
     * caller wanted to stop holding these seats, and they don't (D-016). Someone else's hold is 404,
     * exactly like an unknown id.
     */
    @DeleteMapping("/holds/{id}")
    @ApiResponse(responseCode = "204", description = "Released, or had already been released or expired")
    @ErrorResponses({ErrorCode.VALIDATION_FAILED, ErrorCode.UNAUTHENTICATED, ErrorCode.HOLD_NOT_FOUND,
            ErrorCode.HOLD_CONVERTED})
    ResponseEntity<Void> cancel(@PathVariable UUID id, @AuthenticationPrincipal CurrentUser user) {
        return switch (holds.cancel(user.userId(), id)) {
            case CancelResult.Cancelled cancelled -> ResponseEntity.noContent().build();
            case CancelResult.AlreadyEnded ended -> ResponseEntity.noContent().build();
            case CancelResult.NotFound notFound ->
                    throw new ApiException(ErrorCode.HOLD_NOT_FOUND, "No hold with id " + id + ".");
            case CancelResult.Converted converted -> throw new ApiException(ErrorCode.HOLD_CONVERTED,
                    "This hold has already become an order.");
        };
    }

    private static HoldResponse response(HoldView hold) {
        return new HoldResponse(hold.holdId(), hold.eventId(), hold.seatIds(), hold.expiresAt(), Instant.now());
    }

    /** VALIDATION_FAILED with a field error on seatIds, the same shape bean validation produces. */
    private static ApiException invalidSeatIds(String message) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, "The request body is invalid.",
                Map.of("errors", List.of(Map.of("field", "seatIds", "message", message))));
    }
}
