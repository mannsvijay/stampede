package com.stampede.shared.error;

import org.springframework.http.HttpStatus;

/**
 * Stable error codes from PRD section 10. Clients branch on {@link #name()}, never on the message,
 * so a code is never renamed once released. Codes are added with the task that first returns them.
 */
public enum ErrorCode {
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "Validation failed"),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "Authentication required"),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "Invalid email or password"),
    INVALID_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED, "Invalid refresh token"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "Forbidden"),
    EMAIL_TAKEN(HttpStatus.CONFLICT, "Email already registered");

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
