package com.stampede.control.admin;

import com.stampede.inventory.api.EventStrategy;
import com.stampede.inventory.api.StrategySwitch;
import com.stampede.inventory.api.SwitchResult;
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
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * POST /admin/events/{id}/strategy (PRD 10, OP2, M2.3a, D-023). ADMIN only: SecurityConfig's
 * {@code /admin/**} rule answers 401 without a token and 403 for any other role, before this runs.
 * Every switch writes one audit_log row, in the same transaction as the switch.
 */
@Tag(name = "admin")
@RestController
class AdminEventController {

    record SwitchStrategyRequest(@NotNull EventStrategy strategy) {
    }

    /**
     * @param strategy the event's strategy now
     * @param previous its strategy before this request (equal to {@code strategy} when unchanged)
     * @param changed  false when the event already used the requested strategy (no audit row then)
     */
    record SwitchStrategyResponse(UUID eventId, EventStrategy strategy, EventStrategy previous, boolean changed) {
    }

    private final StrategySwitch strategies;
    private final AuditLog auditLog;

    AdminEventController(StrategySwitch strategies, AuditLog auditLog) {
        this.strategies = strategies;
        this.auditLog = auditLog;
    }

    /**
     * Switches the event's inventory strategy. Allowed only while the event takes no holds (before its
     * drop, or DRAFT, PAUSED or SOLD_OUT) and has no live hold; otherwise 409 with the reason.
     */
    @PostMapping("/admin/events/{id}/strategy")
    @ApiResponse(responseCode = "200", description = "Switched, or the event already used this strategy",
            content = @Content(schema = @Schema(implementation = SwitchStrategyResponse.class)))
    @ErrorResponses({ErrorCode.VALIDATION_FAILED, ErrorCode.UNAUTHENTICATED, ErrorCode.FORBIDDEN,
            ErrorCode.EVENT_NOT_FOUND, ErrorCode.STRATEGY_SWITCH_REFUSED})
    SwitchStrategyResponse switchStrategy(@PathVariable UUID id, @Valid @RequestBody SwitchStrategyRequest request,
                                          @AuthenticationPrincipal CurrentUser admin) {
        SwitchResult result = strategies.switchStrategy(id, request.strategy(),
                (eventId, from, to) -> auditLog.strategyChanged(admin.userId(), eventId, from, to));
        return switch (result) {
            case SwitchResult.Switched switched ->
                    new SwitchStrategyResponse(id, switched.to(), switched.from(), true);
            case SwitchResult.Unchanged unchanged ->
                    new SwitchStrategyResponse(id, unchanged.strategy(), unchanged.strategy(), false);
            case SwitchResult.EventNotFound notFound ->
                    throw new ApiException(ErrorCode.EVENT_NOT_FOUND, "No event with id " + id + ".");
            case SwitchResult.Refused refused -> throw new ApiException(ErrorCode.STRATEGY_SWITCH_REFUSED,
                    message(refused.reason()),
                    Map.of("reason", refused.reason().name(), "strategy", refused.strategy().name()));
        };
    }

    private static String message(SwitchResult.Reason reason) {
        return switch (reason) {
            case ON_SALE -> "The event is on sale. Switch before its drop, or while it is paused.";
            case EVENT_ENDED -> "The event has ended.";
            case LIVE_HOLDS -> "The event has active holds. Try again once they have ended.";
            case LIVE_REDIS_HOLDS -> "Redis still has holds for this event. Try again once they have ended.";
            case UNAPPLIED_STREAM_ENTRIES ->
                    "Some of this event's holds have not reached the database yet. Try again in a moment.";
            case SWITCH_IN_PROGRESS -> "Another strategy switch for this event is running.";
        };
    }
}
