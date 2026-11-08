package com.stampede.identity.token;

import java.time.Duration;
import java.util.Base64;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Auth settings ({@code stampede.auth.*}). The constructor validates them, so a missing or weak
 * signing key stops the application at startup instead of failing on the first request.
 */
@ConfigurationProperties("stampede.auth")
public record AuthProperties(
        String jwtSecret,
        String issuer,
        Duration accessTokenTtl,
        Duration refreshTokenTtl,
        Duration refreshFamilyMaxAge) {

    /** HS256 needs a key at least as long as its 256-bit output (RFC 7518, section 3.2). */
    static final int MIN_SECRET_BYTES = 32;

    public AuthProperties {
        if (jwtSecret == null || jwtSecret.isBlank()) {
            throw new IllegalArgumentException("stampede.auth.jwt-secret is not set. "
                    + "Set STAMPEDE_JWT_SECRET to the output of: openssl rand -base64 32");
        }
        byte[] key;
        try {
            key = Base64.getDecoder().decode(jwtSecret.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "STAMPEDE_JWT_SECRET is not valid base64. Generate one with: openssl rand -base64 32");
        }
        if (key.length < MIN_SECRET_BYTES) {
            throw new IllegalArgumentException("STAMPEDE_JWT_SECRET decodes to " + key.length
                    + " bytes; HS256 needs at least " + MIN_SECRET_BYTES);
        }
        if (issuer == null || issuer.isBlank()) {
            throw new IllegalArgumentException("stampede.auth.issuer is not set");
        }
        requirePositive("access-token-ttl", accessTokenTtl);
        requirePositive("refresh-token-ttl", refreshTokenTtl);
        requirePositive("refresh-family-max-age", refreshFamilyMaxAge);
        if (refreshTokenTtl.compareTo(refreshFamilyMaxAge) > 0) {
            throw new IllegalArgumentException("refresh-token-ttl must not exceed refresh-family-max-age");
        }
    }

    public SecretKey signingKey() {
        return new SecretKeySpec(Base64.getDecoder().decode(jwtSecret.trim()), "HmacSHA256");
    }

    private static void requirePositive(String name, Duration value) {
        if (value == null || value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException("stampede.auth." + name + " must be positive");
        }
    }
}
