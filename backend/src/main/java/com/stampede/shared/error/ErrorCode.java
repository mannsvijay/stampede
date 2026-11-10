package com.stampede.shared.error;

import org.springframework.http.HttpStatus;

/**
 * Stable error codes from PRD section 10. Clients branch on {@link #name()}, never on the message,
 * so a code is never renamed once released. Codes are added with the task that first returns them.
 * This is one central list on purpose (D-010): it mirrors the PRD table and the OpenAPI spec.
 */
public enum ErrorCode {
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "Validation failed"),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "Authentication required"),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "Invalid email or password"),
    INVALID_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED, "Invalid refresh token"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "Forbidden"),
    EMAIL_TAKEN(HttpStatus.CONFLICT, "Email already registered"),
    EVENT_NOT_FOUND(HttpStatus.NOT_FOUND, "Event not found"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "Not found"),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "Method not allowed"),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Unsupported media type"),
    /** A requested seat is HELD or SOLD already. Body: {@code seatIds}, {@code takenAgoMs} (M1.4). */
    SEAT_TAKEN(HttpStatus.CONFLICT, "Seat taken"),
    /** The hold would take the user over the event's per-user limit. Body: {@code limit} (M1.4). */
    LIMIT_EXCEEDED(HttpStatus.CONFLICT, "Ticket limit exceeded"),
    /** The event's sale hasn't opened (before its drop) or isn't running (M1.4). */
    SALE_NOT_OPEN(HttpStatus.CONFLICT, "Sale not open"),
    /** No hold with this id belongs to the caller; someone else's hold looks the same (M1.4). */
    HOLD_NOT_FOUND(HttpStatus.NOT_FOUND, "Hold not found"),
    /** The hold became an order, so it can no longer be released (M1.4). */
    HOLD_CONVERTED(HttpStatus.CONFLICT, "Hold converted to an order"),
    /**
     * Any other client error the framework rejects (e.g. 406). The response keeps the framework's
     * status; {@link #status()} is only a nominal default.
     */
    REQUEST_FAILED(HttpStatus.BAD_REQUEST, "Request failed"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error");

    private final HttpStatus status;
    private final String title;

    ErrorCode(HttpStatus status, String title) {
        this.status = status;
        this.title = title;
    }

    public HttpStatus status() {
        return status;
    }

    public String title() {
        return title;
    }
}
