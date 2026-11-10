package com.stampede.shared.error;

import java.util.Map;

/**
 * Thrown by any module to return a problem+json response with a stable error code. Optional
 * {@code properties} are added to the problem body next to {@code code} (e.g. SEAT_TAKEN's
 * {@code seatIds} and {@code takenAgoMs}); each one must be documented in the OpenAPI Problem schema.
 */
public class ApiException extends RuntimeException {

    private final ErrorCode code;
    private final Map<String, Object> properties;

    public ApiException(ErrorCode code, String detail) {
        this(code, detail, Map.of());
    }

    public ApiException(ErrorCode code, String detail, Map<String, Object> properties) {
        super(detail);
        this.code = code;
        this.properties = Map.copyOf(properties);
    }

    public ErrorCode code() {
        return code;
    }

    /** Extra problem properties; never contains the reserved RFC 7807 members or {@code code}. */
    public Map<String, Object> properties() {
        return properties;
    }
}
