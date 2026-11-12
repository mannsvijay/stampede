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
    /**
     * Other buyers kept changing these seats while the request tried to hold them (optimistic
     * strategy, D-021); nothing was held. Body: {@code seatIds}. Header {@code Retry-After: 1} (M2.1).
     */
    SEAT_CONTENDED(HttpStatus.CONFLICT, "Seats contended", 1),
    /** The hold would take the user over the event's per-user limit. Body: {@code limit} (M1.4). */
    LIMIT_EXCEEDED(HttpStatus.CONFLICT, "Ticket limit exceeded"),
    /** The event's sale hasn't opened (before its drop) or isn't running (M1.4). */
    SALE_NOT_OPEN(HttpStatus.CONFLICT, "Sale not open"),
    /** No hold with this id belongs to the caller; someone else's hold looks the same (M1.4). */
    HOLD_NOT_FOUND(HttpStatus.NOT_FOUND, "Hold not found"),
    /** The hold became an order, so it can no longer be released (M1.4). */
    HOLD_CONVERTED(HttpStatus.CONFLICT, "Hold converted to an order"),
    /**
     * The hold expired or was released before it could become an order; nothing was sold and any
     * payment taken was refunded (M1.5, D-016 chose 410 Gone).
     */
    HOLD_EXPIRED(HttpStatus.GONE, "Hold expired"),
    /** The payment provider declined the charge; the order is cancelled and the seats are released (M1.5). */
    PAYMENT_FAILED(HttpStatus.PAYMENT_REQUIRED, "Payment failed"),
    /** No order with this id belongs to the caller; someone else's order looks the same (M1.5). */
    ORDER_NOT_FOUND(HttpStatus.NOT_FOUND, "Order not found"),
    /**
     * Any other client error the framework rejects (e.g. 406). The response keeps the framework's
     * status; {@link #status()} is only a nominal default.
     */
    REQUEST_FAILED(HttpStatus.BAD_REQUEST, "Request failed"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error");

    private final HttpStatus status;
    private final String title;
    private final Integer retryAfterSeconds;

    ErrorCode(HttpStatus status, String title) {
        this(status, title, null);
    }

    ErrorCode(HttpStatus status, String title, Integer retryAfterSeconds) {
        this.status = status;
        this.title = title;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public HttpStatus status() {
        return status;
    }

    /** Seconds for a {@code Retry-After} header sent with every response of this code, or null for none. */
    public Integer retryAfterSeconds() {
        return retryAfterSeconds;
    }

    public String title() {
        return title;
    }
}
