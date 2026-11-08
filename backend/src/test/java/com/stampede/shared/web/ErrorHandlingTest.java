package com.stampede.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;

import com.stampede.testsupport.ApiTestSupport;

/**
 * The application-wide problem+json handling (D-010): framework errors, unexpected exceptions, and
 * errors that bypass Spring MVC and land on /error. The failing routes are test-only probes
 * (testsupport.ProbeController).
 */
class ErrorHandlingTest extends ApiTestSupport {

    private String token;

    @BeforeEach
    void logIn() {
        token = registerAndLogin(uniqueEmail()).accessToken();
    }

    // ---------------------------------------------------------------------------------------------
    // Routing errors
    // ---------------------------------------------------------------------------------------------

    @Test
    void unknownRouteWithTokenIs404NotFound() {
        Response response = get("/me/no-such-route", token);

        assertProblem(response, 404, "NOT_FOUND");
        assertThat(response.body().path("instance").asText()).isEqualTo("/me/no-such-route");
    }

    /** Security runs before routing: without a token, an unknown route is 401, not 404 (PRD 10). */
    @Test
    void unknownRouteWithoutTokenIs401() {
        assertProblem(get("/me/no-such-route", null), 401, "UNAUTHENTICATED");
    }

    @Test
    void wrongMethodIs405WithAllowHeader() {
        Response response = request(HttpMethod.DELETE, "/events", token);

        assertProblem(response, 405, "METHOD_NOT_ALLOWED");
        assertThat(response.headers().getAllow()).contains(HttpMethod.GET);
    }

    @Test
    void unsupportedContentTypeIs415() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_PLAIN);

        Response response = exchange(HttpMethod.POST, "/auth/login", new HttpEntity<>("hello", headers));

        assertProblem(response, 415, "UNSUPPORTED_MEDIA_TYPE");
    }

    // ---------------------------------------------------------------------------------------------
    // Unexpected errors never leak internals
    // ---------------------------------------------------------------------------------------------

    @Test
    void unexpectedExceptionIs500WithoutItsMessage() {
        Response response = get("/me/_boom", token);

        assertInternalErrorWithoutLeaks(response);
    }

    @Test
    void sqlErrorIs500WithoutSqlOrTableNames() {
        Response response = get("/me/_bad-sql", token);

        assertInternalErrorWithoutLeaks(response);
        assertThat(response.body().toString()).doesNotContain("no_such_table", "secret_column", "PSQL");
    }

    /** The catch-all handler must not swallow security exceptions into a 500. */
    @Test
    void accessDeniedInsideAControllerIs403() {
        assertProblem(get("/me/_denied", token), 403, "FORBIDDEN");
    }

    // ---------------------------------------------------------------------------------------------
    // Errors outside Spring MVC go through /error
    // ---------------------------------------------------------------------------------------------

    @Test
    void sendErrorIsProblemJsonWithoutTheMessage() {
        Response response = get("/me/_send-error", token);

        assertProblem(response, 418, "REQUEST_FAILED");
        assertThat(response.body().path("instance").asText()).isEqualTo("/me/_send-error");
        assertThat(response.body().toString()).doesNotContain("password_hash", "SELECT");
    }

    @Test
    void requestingErrorPathDirectlyIs404() {
        assertProblem(get("/error", null), 404, "NOT_FOUND");
    }

    // ---------------------------------------------------------------------------------------------
    // OpenAPI endpoint
    // ---------------------------------------------------------------------------------------------

    /** Only the `openapi` profile serves the spec (D-010); OpenApiSpecTest covers that side. */
    @Test
    void apiDocsAreNotServedByDefault() {
        assertProblem(get("/v3/api-docs", null), 404, "NOT_FOUND");
    }

    // ---------------------------------------------------------------------------------------------

    private static void assertProblem(Response response, int status, String code) {
        assertThat(response.status()).isEqualTo(status);
        assertThat(response.headers().getContentType()).isNotNull();
        assertThat(response.headers().getContentType().isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .as("content type %s", response.headers().getContentType()).isTrue();
        assertThat(response.code()).isEqualTo(code);
        assertThat(response.body().path("status").asInt()).isEqualTo(status);
    }

    private static void assertInternalErrorWithoutLeaks(Response response) {
        assertProblem(response, 500, "INTERNAL_ERROR");
        assertThat(response.body().path("errorId").asText()).matches("[0-9a-f-]{36}");
        String body = response.body().toString();
        assertThat(body).doesNotContain("SELECT", "password_hash", "Exception", "at com.", "at org.",
                "trace", "stampede.");
    }
}
