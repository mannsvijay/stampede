package com.stampede.shared.web;

import java.net.URI;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.stampede.shared.error.ErrorCode;
import com.stampede.shared.error.Problems;

import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Replaces Spring Boot's default {@code /error} controller, which answers with its own JSON shape
 * ({@code timestamp, status, error, path}) instead of problem+json.
 *
 * <p>Errors that happen outside Spring MVC never reach {@link ApiExceptionHandler}: an exception
 * thrown by a servlet filter, or code calling {@code response.sendError(...)}. The servlet container
 * forwards those to {@code /error}. This controller looks only at the status, never the exception's
 * message, so nothing internal leaks.
 */
@Hidden  // not part of the API contract (OpenAPI)
@RestController
class ProblemErrorController implements ErrorController {

    private static final Logger log = LoggerFactory.getLogger(ProblemErrorController.class);

    @RequestMapping("${server.error.path:${error.path:/error}}")
    ResponseEntity<ProblemDetail> error(HttpServletRequest request) {
        Object statusAttribute = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        // No status attribute means someone requested /error directly: there is nothing here.
        HttpStatusCode status = statusAttribute instanceof Integer code
                ? HttpStatusCode.valueOf(code)
                : HttpStatus.NOT_FOUND;

        ErrorCode code = StatusCodes.forStatus(status);
        ProblemDetail problem = Problems.of(code, status, StatusCodes.detailFor(code));
        if (status.is5xxServerError()) {
            String errorId = UUID.randomUUID().toString();
            log.error("Error {} forwarded to /error for {}", errorId,
                    request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI),
                    (Throwable) request.getAttribute(RequestDispatcher.ERROR_EXCEPTION));
            problem.setProperty("errorId", errorId);
        }
        // On an error dispatch the request URI is /error; report the path the client actually called.
        Object originalUri = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
        problem.setInstance(URI.create(originalUri instanceof String uri ? uri : request.getRequestURI()));
        return ResponseEntity.status(status).body(problem);
    }
}
