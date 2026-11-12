package com.stampede.shared.web;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import com.stampede.shared.error.ApiException;
import com.stampede.shared.error.ErrorCode;
import com.stampede.shared.error.Problems;

/**
 * Turns every exception thrown by a controller, in any module, into RFC 7807 problem+json with a
 * stable {@code code} (PRD 10).
 *
 * <p>{@link ResponseEntityExceptionHandler} is Spring's base class that already maps the standard
 * MVC errors to the right status (unknown route 404, wrong method 405 with an {@code Allow} header,
 * unsupported content type 415, malformed path variable 400, ...). All of them pass through
 * {@link #handleExceptionInternal}, where we add our code and replace the detail with a fixed text.
 *
 * <p>Anything else is a bug: it becomes a 500 whose body has only a generic message and an
 * {@code errorId}; the exception itself (message, SQL, stack trace) goes only to the log under that id.
 */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ProblemDetail> api(ApiException e) {
        ProblemDetail problem = Problems.of(e.code(), e.getMessage());
        e.properties().forEach(problem::setProperty);
        ResponseEntity.BodyBuilder response = ResponseEntity.status(e.code().status());
        if (e.code().retryAfterSeconds() != null) {
            response.header(HttpHeaders.RETRY_AFTER, String.valueOf(e.code().retryAfterSeconds()));
        }
        return response.body(problem);
    }

    /**
     * Security exceptions thrown inside a controller (e.g. by method security later) must reach
     * Spring Security's filter, which answers 401/403. Rethrowing the same exception tells Spring
     * MVC this handler declines it.
     */
    @ExceptionHandler({AccessDeniedException.class, AuthenticationException.class})
    void security(RuntimeException e) {
        throw e;
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> unexpected(Exception e, WebRequest request) {
        return ResponseEntity.internalServerError().body(internalError(e, request));
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException e, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<Map<String, String>> errors = e.getBindingResult().getFieldErrors().stream()
                .sorted(Comparator.comparing(f -> f.getField()))
                .map(f -> Map.of("field", f.getField(),
                        "message", f.getDefaultMessage() == null ? "is invalid" : f.getDefaultMessage()))
                .toList();
        ProblemDetail problem = Problems.of(ErrorCode.VALIDATION_FAILED, "The request body is invalid.");
        problem.setProperty("errors", errors);
        return handleExceptionInternal(e, problem, headers, status, request);
    }

    /**
     * The single exit for every standard MVC exception. Replaces Spring's default body (whose detail
     * can echo internals) with our code and a fixed message, unless a handler above built one.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception e, Object body, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail problem;
        if (body instanceof ProblemDetail p && p.getProperties() != null && p.getProperties().containsKey("code")) {
            problem = p;
        } else if (status.is5xxServerError()) {
            problem = internalError(e, request);
        } else {
            ErrorCode code = StatusCodes.forStatus(status);
            problem = Problems.of(code, status, detail(e, code));
        }
        return super.handleExceptionInternal(e, problem, headers, status, request);
    }

    private static String detail(Exception e, ErrorCode code) {
        if (e instanceof TypeMismatchException mismatch && mismatch.getPropertyName() != null) {
            return "The value of '" + mismatch.getPropertyName() + "' is not valid.";
        }
        if (e instanceof HttpMessageNotReadableException) {
            return "The request body is not valid JSON.";
        }
        return StatusCodes.detailFor(code);
    }

    private static ProblemDetail internalError(Exception e, WebRequest request) {
        String errorId = UUID.randomUUID().toString();
        log.error("Unhandled error {} on {}", errorId, request.getDescription(false), e);
        ProblemDetail problem = Problems.of(ErrorCode.INTERNAL_ERROR,
                StatusCodes.detailFor(ErrorCode.INTERNAL_ERROR) + " Quote the errorId if you report it.");
        problem.setProperty("errorId", errorId);
        return problem;
    }
}
