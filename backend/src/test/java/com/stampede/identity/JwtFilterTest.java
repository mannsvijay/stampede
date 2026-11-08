package com.stampede.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.stampede.identity.token.AuthProperties;
import com.stampede.testsupport.ApiTestSupport;

/** The JWT filter and route rules: public routes, 401 for bad tokens, 403 for non-admins. */
class JwtFilterTest extends ApiTestSupport {

    @Autowired
    JwtEncoder appEncoder;

    @Autowired
    AuthProperties authProperties;

    @Test
    void testsUseTheTestSigningKeyNotADevelopersEnv() {
        assertThat(authProperties.jwtSecret()).isEqualTo("c3RhbXBlZGUtdGVzdC1vbmx5LXNpZ25pbmcta2V5LTA=");
    }

    // ---------------------------------------------------------------------------------------------
    // Public routes
    // ---------------------------------------------------------------------------------------------

    @Test
    void publicRoutesNeedNoToken() {
        assertThat(get("/actuator/health", null).status()).isEqualTo(200);
        // No /events controller until M0.6: 404 (not 401) shows security let the request through.
        assertThat(get("/events", null).status()).isEqualTo(404);
        assertThat(get("/events/" + UUID.randomUUID(), null).status()).isEqualTo(404);
    }

    @Test
    void onlyGetOnEventsIsPublic() {
        assertThat(post("/events", "{}", null, false).status()).isEqualTo(401);
    }

    // ---------------------------------------------------------------------------------------------
    // 401: missing or bad token
    // ---------------------------------------------------------------------------------------------

    @Test
    void protectedRouteWithoutTokenIs401ProblemJson() {
        Response response = get("/me/_probe", null);

        assertThat(response.status()).isEqualTo(401);
        assertThat(response.code()).isEqualTo("UNAUTHENTICATED");
        assertThat(response.headers().getContentType().toString()).isEqualTo("application/problem+json");
        assertThat(response.headers().getFirst(HttpHeaders.WWW_AUTHENTICATE)).startsWith("Bearer");
        assertThat(response.body().path("instance").asText()).isEqualTo("/me/_probe");
    }

    @Test
    void unknownRoutesAreProtectedToo() {
        assertThat(get("/orders/" + UUID.randomUUID(), null).status()).isEqualTo(401);
    }

    @Test
    void expiredTokenIs401() {
        // Expired 2 minutes ago: beyond the 30-second clock-skew allowance.
        Instant past = Instant.now().minusSeconds(20 * 60);
        String token = sign(appEncoder, claims(UUID.randomUUID(), "USER", past, past.plusSeconds(18 * 60)));

        assertUnauthenticated(token);
    }

    @Test
    void tokenExpiredWithinTheClockSkewIsStillAccepted() {
        Instant now = Instant.now();
        String token = sign(appEncoder, claims(UUID.randomUUID(), "USER", now.minusSeconds(900), now.minusSeconds(10)));

        assertThat(get("/me/_probe", token).status()).isEqualTo(200);
    }

    @Test
    void tamperedPayloadIs401() {
        String token = registerAndLogin(uniqueEmail()).accessToken();
        String[] parts = token.split("\\.");
        // Swap the payload for one claiming ADMIN, keeping the original signature.
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8)
                .replace("\"USER\"", "\"ADMIN\"");
        String forged = parts[0] + "." + base64Url(payload) + "." + parts[2];

        assertUnauthenticated(forged);
        assertThat(get("/admin/_probe", forged).status()).isEqualTo(401);
    }

    @Test
    void tamperedSignatureIs401() {
        String token = registerAndLogin(uniqueEmail()).accessToken();
        char last = token.charAt(token.length() - 1);
        String tampered = token.substring(0, token.length() - 1) + (last == 'A' ? 'B' : 'A');

        assertUnauthenticated(tampered);
    }

    @Test
    void tokenSignedWithAnotherKeyIs401() {
        byte[] otherKey = "another-key-that-is-32-bytes-ok!".getBytes(StandardCharsets.US_ASCII);
        JwtEncoder otherEncoder = new NimbusJwtEncoder(
                new ImmutableSecret<>(new SecretKeySpec(otherKey, "HmacSHA256")));
        Instant now = Instant.now();

        assertUnauthenticated(sign(otherEncoder, claims(UUID.randomUUID(), "ADMIN", now, now.plusSeconds(600))));
    }

    @Test
    void unsignedAlgNoneTokenIs401() {
        long now = Instant.now().getEpochSecond();
        String header = base64Url("{\"alg\":\"none\",\"typ\":\"JWT\"}");
        String payload = base64Url("{\"iss\":\"stampede\",\"sub\":\"" + UUID.randomUUID()
                + "\",\"role\":\"ADMIN\",\"iat\":" + now + ",\"exp\":" + (now + 600) + "}");

        assertUnauthenticated(header + "." + payload + ".");
    }

    @Test
    void wrongIssuerIs401() {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("someone-else").subject(UUID.randomUUID().toString())
                .issuedAt(now).expiresAt(now.plusSeconds(600)).claim("role", "USER").build();

        assertUnauthenticated(sign(appEncoder, claims));
    }

    @Test
    void validSignatureWithMalformedClaimsIs401() {
        Instant now = Instant.now();

        assertUnauthenticated(sign(appEncoder, claims(UUID.randomUUID(), "SUPERUSER", now, now.plusSeconds(600))));
    }

    @Test
    void malformedBearerValueIs401() {
        assertUnauthenticated("not-a-jwt");
    }

    // ---------------------------------------------------------------------------------------------
    // 403: admin routes
    // ---------------------------------------------------------------------------------------------

    @Test
    void nonAdminOnAdminRouteIs403ProblemJson() {
        String token = registerAndLogin(uniqueEmail()).accessToken();

        Response response = get("/admin/_probe", token);

        assertThat(response.status()).isEqualTo(403);
        assertThat(response.code()).isEqualTo("FORBIDDEN");
        assertThat(response.headers().getContentType().toString()).isEqualTo("application/problem+json");
    }

    @Test
    void adminOnAdminRouteIsAllowed() {
        String email = uniqueEmail();
        register(email, PASSWORD);
        jdbc.sql("UPDATE users SET role = 'ADMIN' WHERE email = ?").param(email).update();
        String token = login(email, PASSWORD).accessToken();

        Response response = get("/admin/_probe", token);

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body().path("role").asText()).isEqualTo("ADMIN");
    }

    @Test
    void roleChangeTakesEffectAtTheNextRefresh() {
        String email = uniqueEmail();
        Response login = registerAndLogin(email);
        jdbc.sql("UPDATE users SET role = 'ADMIN' WHERE email = ?").param(email).update();

        // The old access token still says USER until it is replaced.
        assertThat(get("/admin/_probe", login.accessToken()).status()).isEqualTo(403);
        Response refreshed = refresh(login.refreshCookie());
        assertThat(get("/admin/_probe", refreshed.accessToken()).status()).isEqualTo(200);
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    private void assertUnauthenticated(String token) {
        Response response = get("/me/_probe", token);
        assertThat(response.status()).isEqualTo(401);
        assertThat(response.code()).isEqualTo("UNAUTHENTICATED");
        assertThat(response.body().path("detail").asText()).isEqualTo("The access token is invalid or expired.");
    }

    private static JwtClaimsSet claims(UUID userId, String role, Instant issuedAt, Instant expiresAt) {
        return JwtClaimsSet.builder()
                .issuer("stampede")
                .subject(userId.toString())
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .claim("role", role)
                .build();
    }

    private static String sign(JwtEncoder encoder, JwtClaimsSet claims) {
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    private static String base64Url(String json) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }
}
