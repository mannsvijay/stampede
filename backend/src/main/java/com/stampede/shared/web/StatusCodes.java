package com.stampede.shared.web;

import org.springframework.http.HttpStatusCode;

import com.stampede.shared.error.ErrorCode;

/** The stable code for an error we only know by its HTTP status (framework errors, /error). */
final class StatusCodes {

    private StatusCodes() {
    }

    static ErrorCode forStatus(HttpStatusCode status) {
        if (status.is5xxServerError()) {
            return ErrorCode.INTERNAL_ERROR;
        }
        return switch (status.value()) {
            case 400 -> ErrorCode.VALIDATION_FAILED;
            case 401 -> ErrorCode.UNAUTHENTICATED;
            case 403 -> ErrorCode.FORBIDDEN;
            case 404 -> ErrorCode.NOT_FOUND;
            case 405 -> ErrorCode.METHOD_NOT_ALLOWED;
            case 415 -> ErrorCode.UNSUPPORTED_MEDIA_TYPE;
            default -> ErrorCode.REQUEST_FAILED;
        };
    }

    /** A fixed, safe message per code: never the exception's own message. */
    static String detailFor(ErrorCode code) {
        return switch (code) {
            case VALIDATION_FAILED -> "The request is invalid.";
            case UNAUTHENTICATED -> "This request requires a bearer access token.";
            case FORBIDDEN -> "You don't have permission to access this resource.";
            case NOT_FOUND -> "No resource exists at this path.";
            case METHOD_NOT_ALLOWED -> "This path does not support the request method.";
            case UNSUPPORTED_MEDIA_TYPE -> "The request body's content type is not supported.";
            case INTERNAL_ERROR -> "An unexpected error occurred.";
            default -> "The request could not be processed.";
        };
    }
}
