package com.stampede.control.admin;

import com.stampede.control.invariants.OnDemandInvariantCheck;
import com.stampede.inventory.api.EventStrategy;
import com.stampede.shared.error.ApiException;
import com.stampede.shared.error.ErrorCode;
import com.stampede.shared.error.ErrorResponses;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * GET /admin/invariants?eventId=...&grace=... (M2.4a, D-024): runs the invariant checker (PRD 6.10,
 * D-019) for one event now and returns every violation. ADMIN only: SecurityConfig's {@code /admin/**}
 * rule answers 401 without a token and 403 for any other role, before this runs.
 * <p>
 * k6.sh calls it after a load run with {@code grace=PT0S}, asking again until
 * {@code unappliedStreamEntries} is false. Without {@code grace} the monitor's grace applies, so an
 * operator looking during a live sale doesn't see checkouts still in progress as violations.
 */
@Tag(name = "admin")
@RestController
class AdminInvariantsController {

    /**
     * @param unappliedStreamEntries read before the check: true while the Redis inventory stream still has
     *                               entries of this REDIS_FIRST event that Postgres hasn't received; the
     *                               violations are then not conclusive (I10 may only see that lag)
     * @param grace                  ISO-8601; PT0S = strict
     */
    record InvariantsResponse(UUID eventId, EventStrategy strategy, boolean unappliedStreamEntries, String grace,
                              Instant checkedAt, List<String> rules, List<ViolationResponse> violations) {
    }

    record ViolationResponse(String rule, String name, String detail) {
    }

    private final OnDemandInvariantCheck invariants;
    private final Duration defaultGrace;

    AdminInvariantsController(OnDemandInvariantCheck invariants,
                              @Value("${stampede.invariants.monitor.grace:PT30S}") Duration defaultGrace) {
        this.invariants = invariants;
        this.defaultGrace = defaultGrace;
    }

    @GetMapping("/admin/invariants")
    @Operation(summary = "Check one event's invariants now",
            description = "Runs every invariant rule for the event and returns the violations (none = all hold). "
                    + "For a REDIS_FIRST event, the result is only conclusive when unappliedStreamEntries is false.")
    @ApiResponse(responseCode = "200", description = "Checked; violations is empty when every rule holds",
            content = @Content(schema = @Schema(implementation = InvariantsResponse.class)))
    @ErrorResponses({ErrorCode.VALIDATION_FAILED, ErrorCode.UNAUTHENTICATED, ErrorCode.FORBIDDEN,
            ErrorCode.EVENT_NOT_FOUND})
    InvariantsResponse checkInvariants(
            @RequestParam UUID eventId,
            @Parameter(description = "ISO-8601 duration, e.g. PT0S (strict) or PT30S. In-between checkout states of "
                    + "orders younger than this are excused. Default: the invariant monitor's grace.")
            @RequestParam(required = false) String grace) {
        Duration checkGrace = grace == null ? defaultGrace : parseGrace(grace);
        OnDemandInvariantCheck.Result result = invariants.check(eventId, checkGrace)
                .orElseThrow(() -> new ApiException(ErrorCode.EVENT_NOT_FOUND, "No event with id " + eventId + "."));
        return new InvariantsResponse(result.eventId(), EventStrategy.valueOf(result.strategy()),
                result.unappliedStreamEntries(), result.grace().toString(), result.checkedAt(), result.rules(),
                result.violations().stream()
                        .map(v -> new ViolationResponse(v.ruleId(), v.ruleName(), v.detail()))
                        .toList());
    }

    private static Duration parseGrace(String grace) {
        Duration parsed;
        try {
            parsed = Duration.parse(grace);
        } catch (DateTimeParseException e) {
            throw invalidGrace("must be an ISO-8601 duration such as PT0S or PT30S");
        }
        if (parsed.isNegative()) {
            throw invalidGrace("must not be negative");
        }
        return parsed;
    }

    /** VALIDATION_FAILED with a field error on grace, the same shape bean validation produces. */
    private static ApiException invalidGrace(String message) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, "The request is invalid.",
                Map.of("errors", List.of(Map.of("field", "grace", "message", message))));
    }
}
