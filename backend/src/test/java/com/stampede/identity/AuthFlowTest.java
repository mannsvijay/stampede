package com.stampede.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.HttpCookie;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.stampede.testsupport.ApiTestSupport;

/** Register, login, refresh rotation, reuse detection and logout over real HTTP. */
class AuthFlowTest extends ApiTestSupport {

    // ---------------------------------------------------------------------------------------------
    // Register and login
    // ---------------------------------------------------------------------------------------------

    @Test
    void registerThenLoginWorksAndTheAccessTokenIdentifiesTheUser() {
        String email = uniqueEmail();

        Response registered = register(email, PASSWORD);
        assertThat(registered.status()).isEqualTo(201);
        assertThat(registered.body().path("email").asText()).isEqualTo(email);
        assertThat(registered.body().path("role").asText()).isEqualTo("USER");
        assertThat(registered.body().has("password")).isFalse();
        assertThat(registered.body().has("passwordHash")).isFalse();

        Response login = login(email, PASSWORD);
        assertThat(login.status()).isEqualTo(200);
        assertThat(login.body().path("tokenType").asText()).isEqualTo("Bearer");
        assertThat(login.body().path("expiresIn").asLong()).isEqualTo(15 * 60);
        assertThat(login.body().has("refreshToken")).as("refresh token travels only in the cookie").isFalse();
        assertThat(login.refreshCookie()).isNotBlank();

        Response me = get("/me/_probe", login.accessToken());
        assertThat(me.status()).isEqualTo(200);
        assertThat(me.body().path("userId").asText()).isEqualTo(registered.body().path("id").asText());
        assertThat(me.body().path("role").asText()).isEqualTo("USER");
    }

    @Test
    void emailIsCaseInsensitiveAndTrimmedForLogin() {
        String email = uniqueEmail();
        register(email, PASSWORD);

        assertThat(login("  " + email.toUpperCase() + " ", PASSWORD).status()).isEqualTo(200);
    }

    @Test
    void refreshCookieHasTheSecurityAttributes() {
        Response login = registerAndLogin(uniqueEmail());

        String header = login.setCookieHeader();
        assertThat(header).contains("HttpOnly", "Secure", "SameSite=Strict", "Path=/auth");
        HttpCookie cookie = HttpCookie.parse(header).get(0);
        // 7 days, give or take the time the request took.
        assertThat(cookie.getMaxAge()).isBetween(7L * 24 * 3600 - 60, 7L * 24 * 3600);
    }

    @Test
    void wrongPasswordIsRejected() {
        String email = uniqueEmail();
        register(email, PASSWORD);

        Response response = login(email, "not-the-right-password");

        assertThat(response.status()).isEqualTo(401);
        assertThat(response.code()).isEqualTo("INVALID_CREDENTIALS");
        assertThat(response.headers().getContentType().toString()).isEqualTo("application/problem+json");
        assertThat(response.refreshCookie()).isNull();
    }

    @Test
    void unknownEmailGetsExactlyTheSameResponseAsAWrongPassword() {
        String email = uniqueEmail();
        register(email, PASSWORD);

        Response wrongPassword = login(email, "not-the-right-password");
        Response unknownEmail = login(uniqueEmail(), "not-the-right-password");

        assertThat(unknownEmail.status()).isEqualTo(wrongPassword.status());
        assertThat(unknownEmail.body().path("code")).isEqualTo(wrongPassword.body().path("code"));
        assertThat(unknownEmail.body().path("detail")).isEqualTo(wrongPassword.body().path("detail"));
    }

    @Test
    void duplicateEmailIsRejectedIgnoringCase() {
        String email = uniqueEmail();
        assertThat(register(email, PASSWORD).status()).isEqualTo(201);

        Response duplicate = register(email.toUpperCase(), "another-long-password");

        assertThat(duplicate.status()).isEqualTo(409);
        assertThat(duplicate.code()).isEqualTo("EMAIL_TAKEN");
        assertThat(jdbc.sql("SELECT count(*) FROM users WHERE lower(email) = ?")
                .param(email).query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void invalidRegistrationsAreRejectedWithFieldErrors() {
        Response badEmail = register("not-an-email", PASSWORD);
        assertThat(badEmail.status()).isEqualTo(400);
        assertThat(badEmail.code()).isEqualTo("VALIDATION_FAILED");
        assertThat(badEmail.body().path("errors").get(0).path("field").asText()).isEqualTo("email");

        Response shortPassword = register(uniqueEmail(), "elevenchars");
        assertThat(shortPassword.status()).isEqualTo(400);
        assertThat(shortPassword.body().path("errors").get(0).path("message").asText())
                .isEqualTo("must be at least 12 characters");
    }

    @Test
    void passwordOver72BytesIsRejectedEvenWhenItHasFewCharacters() {
        // 37 characters but 74 bytes: "é" is two bytes in UTF-8. bcrypt would ignore bytes 73+.
        String password = "é".repeat(37);

        Response response = register(uniqueEmail(), password);

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.body().path("errors").get(0).path("message").asText())
                .isEqualTo("must be at most 72 bytes in UTF-8");
    }

    @Test
    void passwordOfExactly72BytesIsAccepted() {
        String email = uniqueEmail();
        String password = "a".repeat(72);

        assertThat(register(email, password).status()).isEqualTo(201);
        assertThat(login(email, password).status()).isEqualTo(200);
    }

    @Test
    void malformedJsonIsAValidationError() {
        Response response = post("/auth/login", "{not json", null, false);

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.code()).isEqualTo("VALIDATION_FAILED");
    }

    // ---------------------------------------------------------------------------------------------
    // Refresh rotation
    // ---------------------------------------------------------------------------------------------

    @Test
    void refreshReturnsANewPairAndTheOldRefreshTokenStopsWorking() {
        Response login = registerAndLogin(uniqueEmail());
        String first = login.refreshCookie();

        Response refreshed = refresh(first);
        assertThat(refreshed.status()).isEqualTo(200);
        String second = refreshed.refreshCookie();
        assertThat(second).isNotBlank().isNotEqualTo(first);
        assertThat(refreshed.accessToken()).isNotEqualTo(login.accessToken());
        assertThat(get("/me/_probe", refreshed.accessToken()).status()).isEqualTo(200);

        Response replay = refresh(first);
        assertThat(replay.status()).isEqualTo(401);
        assertThat(replay.code()).isEqualTo("INVALID_REFRESH_TOKEN");
        assertThat(replay.setCookieHeader()).as("dead cookie is cleared").contains("Max-Age=0");
    }

    @Test
    void reusingAnOldRefreshTokenRevokesTheWholeFamily() {
        Response login = registerAndLogin(uniqueEmail());
        String first = login.refreshCookie();
        String second = refresh(first).refreshCookie();
        String third = refresh(second).refreshCookie();

        // An attacker replays the first token: rejected, and the family is revoked...
        assertThat(refresh(first).status()).isEqualTo(401);

        // ...so even the newest token, which was never used, no longer works.
        Response newest = refresh(third);
        assertThat(newest.status()).isEqualTo(401);
        assertThat(newest.code()).isEqualTo("INVALID_REFRESH_TOKEN");

        Map<String, Object> family = jdbc.sql("""
                        SELECT f.revoke_reason, count(t.id) AS tokens
                        FROM refresh_token_families f JOIN refresh_tokens t ON t.family_id = f.id
                        WHERE f.user_id = ?
                        GROUP BY f.id
                        """)
                .param(userIdOf(login)).query().singleRow();
        assertThat(family).containsEntry("revoke_reason", "REUSE").containsEntry("tokens", 3L);
    }

    @Test
    void reuseRevokesOnlyThatFamilyNotTheUsersOtherSessions() {
        String email = uniqueEmail();
        Response laptop = registerAndLogin(email);
        Response phone = login(email, PASSWORD);

        String laptopFirst = laptop.refreshCookie();
        refresh(laptopFirst);
        refresh(laptopFirst); // reuse: revokes the laptop family

        assertThat(refresh(phone.refreshCookie()).status()).isEqualTo(200);
    }

    @Test
    void rotationChainIsLinkedInTheDatabase() {
        Response login = registerAndLogin(uniqueEmail());
        refresh(login.refreshCookie());

        Map<String, Object> chain = jdbc.sql("""
                        SELECT count(DISTINCT t.family_id) AS families,
                               count(*) FILTER (WHERE t.used_at IS NOT NULL AND t.replaced_by IS NOT NULL) AS consumed,
                               count(*) FILTER (WHERE t.used_at IS NULL) AS live
                        FROM refresh_tokens t JOIN refresh_token_families f ON f.id = t.family_id
                        WHERE f.user_id = ?
                        """)
                .param(userIdOf(login)).query().singleRow();
        assertThat(chain).containsEntry("families", 1L).containsEntry("consumed", 1L).containsEntry("live", 1L);
    }

    @Test
    void expiredRefreshTokenIsRejected() {
        Response login = registerAndLogin(uniqueEmail());
        jdbc.sql("""
                        UPDATE refresh_tokens
                        SET issued_at = now() - interval '8 days', expires_at = now() - interval '1 day'
                        WHERE family_id IN (SELECT id FROM refresh_token_families WHERE user_id = ?)
                        """)
                .param(userIdOf(login)).update();

        assertThat(refresh(login.refreshCookie()).status()).isEqualTo(401);
        // Rejected without side effects: not consumed, and the session isn't revoked as a reuse.
        assertNotConsumedAndNotRevoked(userIdOf(login));
    }

    @Test
    void tokenIsRejectedOnceItsFamilyIsPastTheCapEvenIfTheTokenItselfIsNot() {
        Response login = registerAndLogin(uniqueEmail());
        // The token still has ~7 days, but the session's 30-day cap passed a minute ago.
        jdbc.sql("""
                        UPDATE refresh_token_families
                        SET created_at = now() - interval '30 days', expires_at = now() - interval '1 minute'
                        WHERE user_id = ?
                        """)
                .param(userIdOf(login)).update();

        Response response = refresh(login.refreshCookie());

        assertThat(response.status()).as("401, not a 500 from the expiry CHECK").isEqualTo(401);
        assertThat(response.code()).isEqualTo("INVALID_REFRESH_TOKEN");
        assertNotConsumedAndNotRevoked(userIdOf(login));
    }

    @Test
    void refreshNeverExtendsPastTheFamilyCap() {
        Response login = registerAndLogin(uniqueEmail());
        // Pretend this login was 30 days ago minus one hour: the family has one hour left.
        UUID userId = userIdOf(login);
        jdbc.sql("""
                        UPDATE refresh_token_families
                        SET created_at = now() - interval '30 days', expires_at = now() + interval '1 hour'
                        WHERE user_id = ?
                        """)
                .param(userId).update();

        Response refreshed = refresh(login.refreshCookie());

        assertThat(refreshed.status()).isEqualTo(200);
        // getMaxAge() is -1 for a cookie without Max-Age, so assert a range, not just an upper bound.
        long maxAge = HttpCookie.parse(refreshed.setCookieHeader()).get(0).getMaxAge();
        assertThat(maxAge).as("capped at the family's remaining hour, not 7 days").isBetween(3540L, 3600L);
        // And the server-side expiry, which is what actually counts: exactly the family's cap.
        assertThat(jdbc.sql("""
                        SELECT t.expires_at = f.expires_at
                        FROM refresh_tokens t JOIN refresh_token_families f ON f.id = t.family_id
                        WHERE f.user_id = ? AND t.used_at IS NULL
                        """)
                .param(userId).query(Boolean.class).single()).isTrue();
    }

    @Test
    void refreshWithoutCookieIsRejected() {
        Response response = refresh(null);

        assertThat(response.status()).isEqualTo(401);
        assertThat(response.code()).isEqualTo("INVALID_REFRESH_TOKEN");
    }

    @Test
    void refreshWithGarbageTokenIsRejected() {
        assertThat(refresh("x".repeat(43)).status()).isEqualTo(401);
        assertThat(refresh("short").status()).isEqualTo(401);
    }

    @Test
    void refreshAndLogoutRequireTheCustomHeader() {
        Response login = registerAndLogin(uniqueEmail());

        Response refresh = post("/auth/refresh", null, login.refreshCookie(), false);
        assertThat(refresh.status()).isEqualTo(403);
        assertThat(refresh.code()).isEqualTo("FORBIDDEN");

        Response logout = post("/auth/logout", null, login.refreshCookie(), false);
        assertThat(logout.status()).isEqualTo(403);

        // Neither request consumed or revoked the token.
        assertThat(refresh(login.refreshCookie()).status()).isEqualTo(200);
    }

    // ---------------------------------------------------------------------------------------------
    // Logout
    // ---------------------------------------------------------------------------------------------

    @Test
    void logoutRevokesTheFamilyAndClearsTheCookie() {
        Response login = registerAndLogin(uniqueEmail());
        String second = refresh(login.refreshCookie()).refreshCookie();

        Response logout = logout(second);
        assertThat(logout.status()).isEqualTo(204);
        assertThat(logout.setCookieHeader()).contains("Max-Age=0", "Path=/auth");

        assertThat(refresh(second).status()).isEqualTo(401);
        assertThat(jdbc.sql("SELECT revoke_reason FROM refresh_token_families WHERE user_id = ?")
                .param(userIdOf(login)).query(String.class).single()).isEqualTo("LOGOUT");
    }

    @Test
    void logoutIsIdempotentAndWorksWithoutACookie() {
        Response login = registerAndLogin(uniqueEmail());

        assertThat(logout(login.refreshCookie()).status()).isEqualTo(204);
        assertThat(logout(login.refreshCookie()).status()).isEqualTo(204);
        assertThat(logout(null).status()).isEqualTo(204);
    }

    private void assertNotConsumedAndNotRevoked(UUID userId) {
        Map<String, Object> state = jdbc.sql("""
                        SELECT count(*) FILTER (WHERE t.used_at IS NOT NULL) AS consumed,
                               bool_or(f.revoked_at IS NOT NULL) AS revoked
                        FROM refresh_tokens t JOIN refresh_token_families f ON f.id = t.family_id
                        WHERE f.user_id = ?
                        """)
                .param(userId).query().singleRow();
        assertThat(state).containsEntry("consumed", 0L).containsEntry("revoked", false);
    }

    private UUID userIdOf(Response login) {
        return UUID.fromString(get("/me/_probe", login.accessToken()).body().path("userId").asText());
    }
}
