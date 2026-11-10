package com.stampede.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.HttpCookie;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Base class for HTTP tests against the full app on a random port. Talks to the API the way a
 * browser client would: JSON bodies, the refresh token as a cookie, and the X-Requested-With header
 * on refresh and logout. Cookies are handled by hand so each test controls exactly what it sends.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        // No background expiry while HTTP tests set up holds; HoldExpirySweeperTest drives the sweeper.
        properties = "stampede.holds.sweeper.enabled=false")
@Import(TestcontainersConfiguration.class)
public abstract class ApiTestSupport {

    protected static final String PASSWORD = "correct-horse-battery-staple";
    protected static final String REFRESH_COOKIE = "stampede_refresh";

    @Autowired
    protected TestRestTemplate http;

    @Autowired
    protected JdbcClient jdbc;

    @Autowired
    protected ObjectMapper json;

    /** A response with its body parsed as JSON (null for an empty body). */
    protected record Response(int status, HttpHeaders headers, JsonNode body) {

        /** The stampede_refresh value from Set-Cookie, or null if none was set. */
        public String refreshCookie() {
            List<String> setCookies = headers.getOrDefault(HttpHeaders.SET_COOKIE, List.of());
            return setCookies.stream()
                    .flatMap(header -> HttpCookie.parse(header).stream())
                    .filter(c -> c.getName().equals(REFRESH_COOKIE))
                    .map(HttpCookie::getValue)
                    .findFirst()
                    .orElse(null);
        }

        public String setCookieHeader() {
            return headers.getFirst(HttpHeaders.SET_COOKIE);
        }

        public String code() {
            return body == null ? null : body.path("code").asText(null);
        }

        public String accessToken() {
            return body.path("accessToken").asText();
        }
    }

    protected static String uniqueEmail() {
        return "user-" + UUID.randomUUID() + "@test.local";
    }

    protected Response register(String email, String password) {
        return post("/auth/register", Map.of("email", email, "password", password), null, false);
    }

    protected Response login(String email, String password) {
        return post("/auth/login", Map.of("email", email, "password", password), null, false);
    }

    /** Registers a new user, logs in, and returns the login response (access token + cookie). */
    protected Response registerAndLogin(String email) {
        assertThat(register(email, PASSWORD).status()).isEqualTo(201);
        Response login = login(email, PASSWORD);
        assertThat(login.status()).isEqualTo(200);
        return login;
    }

    protected Response refresh(String refreshToken) {
        return post("/auth/refresh", null, refreshToken, true);
    }

    protected Response logout(String refreshToken) {
        return post("/auth/logout", null, refreshToken, true);
    }

    protected Response post(String path, Object body, String refreshToken, boolean clientHeader) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (refreshToken != null) {
            headers.add(HttpHeaders.COOKIE, REFRESH_COOKIE + "=" + refreshToken);
        }
        if (clientHeader) {
            headers.add("X-Requested-With", "stampede-test");
        }
        return exchange(HttpMethod.POST, path, new HttpEntity<>(body, headers));
    }

    protected Response get(String path, String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        if (accessToken != null) {
            headers.setBearerAuth(accessToken);
        }
        return exchange(HttpMethod.GET, path, new HttpEntity<>(headers));
    }

    /** Any method, no body, optionally with a bearer token. */
    protected Response request(HttpMethod method, String path, String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        if (accessToken != null) {
            headers.setBearerAuth(accessToken);
        }
        return exchange(method, path, new HttpEntity<>(headers));
    }

    protected Response exchange(HttpMethod method, String path, HttpEntity<?> entity) {
        ResponseEntity<String> response = http.exchange(path, method, entity, String.class);
        try {
            JsonNode body = response.getBody() == null ? null : json.readTree(response.getBody());
            return new Response(response.getStatusCode().value(), response.getHeaders(), body);
        } catch (Exception e) {
            throw new AssertionError("Response body is not JSON: " + response.getBody(), e);
        }
    }
}
