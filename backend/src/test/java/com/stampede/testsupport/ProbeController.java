package com.stampede.testsupport;

import java.io.IOException;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.stampede.shared.security.CurrentUser;

import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Test-only endpoints (never shipped, and hidden from the OpenAPI spec): one route that needs any
 * valid token and one under /admin, since no real admin endpoint exists until M2.3. Both echo the
 * resolved CurrentUser. The {@code _boom}-style routes fail on purpose to test the error handling.
 */
@Hidden
@RestController
class ProbeController {

    /** Looks like something a careless error page would leak. */
    static final String SECRET_MESSAGE = "SELECT password_hash FROM users WHERE email = 'victim@test.local'";

    private final JdbcClient jdbc;

    ProbeController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/me/_probe")
    Map<String, String> me(@AuthenticationPrincipal CurrentUser user) {
        return Map.of("userId", user.userId().toString(), "role", user.role().name());
    }

    @GetMapping("/admin/_probe")
    Map<String, String> admin(@AuthenticationPrincipal CurrentUser user) {
        return Map.of("userId", user.userId().toString(), "role", user.role().name());
    }

    /** An unexpected exception whose message must not reach the client. */
    @GetMapping("/me/_boom")
    Map<String, String> boom() {
        throw new IllegalStateException(SECRET_MESSAGE);
    }

    /** A real BadSqlGrammarException: its message contains the SQL and Postgres' error text. */
    @GetMapping("/me/_bad-sql")
    Object badSql() {
        return jdbc.sql("SELECT secret_column FROM no_such_table").query().singleValue();
    }

    /** Thrown inside a controller: must still become Spring Security's 403, not a 500. */
    @GetMapping("/me/_denied")
    Map<String, String> denied() {
        throw new AccessDeniedException("probe denied");
    }

    /** An error outside MVC's exception handling, forwarded by the container to /error. */
    @GetMapping("/me/_send-error")
    void sendError(HttpServletResponse response) throws IOException {
        response.sendError(418, SECRET_MESSAGE);
    }
}
