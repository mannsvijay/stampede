package com.stampede.identity.security;

import java.io.IOException;
import java.net.URI;

import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stampede.shared.error.ErrorCode;
import com.stampede.shared.error.Problems;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Writes 401 and 403 responses from the security filter chain as problem+json. These happen before
 * any controller runs, so a {@code @RestControllerAdvice} can't handle them.
 */
@Component
class ProblemJsonSecurityHandlers implements AuthenticationEntryPoint, AccessDeniedHandler {

    /** Sets the 401 status and the standard WWW-Authenticate: Bearer header (RFC 6750). */
    private final BearerTokenAuthenticationEntryPoint bearer = new BearerTokenAuthenticationEntryPoint();
    private final ObjectMapper objectMapper;

    ProblemJsonSecurityHandlers(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException exception) throws IOException {
        bearer.commence(request, response, exception);
        String detail = exception instanceof OAuth2AuthenticationException
                ? "The access token is invalid or expired."
                : "This request requires a bearer access token.";
        write(request, response, Problems.of(ErrorCode.UNAUTHENTICATED, detail));
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException exception) throws IOException {
        response.setStatus(ErrorCode.FORBIDDEN.status().value());
        write(request, response,
                Problems.of(ErrorCode.FORBIDDEN, "You don't have permission to access this resource."));
    }

    private void write(HttpServletRequest request, HttpServletResponse response, ProblemDetail problem)
            throws IOException {
        problem.setInstance(URI.create(request.getRequestURI()));
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}
