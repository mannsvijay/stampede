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
