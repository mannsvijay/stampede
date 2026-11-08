package com.stampede.identity.token;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.stampede.shared.security.Role;

/**
 * Refresh tokens: issue, rotate with reuse detection, revoke (D-008).
 *
 * <p>A token is 32 random bytes, base64url-encoded. Only its SHA-256 is stored. A fast hash is safe
 * here (unlike for passwords) because 256 random bits can't be guessed, and being deterministic it
 * lets us find the row through the unique index on token_hash.
 *
 * <p>Concurrency: every operation on a family first locks its refresh_token_families row
 * ({@code SELECT ... FOR UPDATE}). That one row is the family's mutex, so rotations and revocations
 * of the same family run one at a time, and each sees everything the previous one committed. No
 * transaction ever locks rows of two families, so there is no lock order to get wrong.
 *
 * <p>All times use the database clock ({@code now()}), so every API instance agrees on expiry.
 */
@Component
public class RefreshTokenStore {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenStore.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int TOKEN_BYTES = 32;
    /** 32 bytes in unpadded base64url are 43 characters; anything else can't be one of ours. */
    private static final int TOKEN_LENGTH = 43;

    public record IssuedRefreshToken(String value, Instant expiresAt) {
    }

    /** A successful rotation: who the token belonged to, and the child token that replaces it. */
    public record Rotation(UUID userId, Role role, IssuedRefreshToken next) {
    }

    private final JdbcClient jdbc;
    private final AuthProperties properties;

    public RefreshTokenStore(JdbcClient jdbc, AuthProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    /** Starts a new family (one per login) and returns its first token. */
    @Transactional
    public IssuedRefreshToken startFamily(UUID userId) {
        UUID familyId = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO refresh_token_families (id, user_id, expires_at)
                        VALUES (:id, :userId, now() + :familySeconds * interval '1 second')
                        """)
                .param("id", familyId)
                .param("userId", userId)
                .param("familySeconds", properties.refreshFamilyMaxAge().toSeconds())
                .update();
        return insertToken(familyId).issued();
    }

    /**
     * Consumes {@code raw} and issues its child. Returns empty if the token is unknown, expired,
     * already used, or its family is revoked. An already-used token is a reuse: the family is
     * revoked.
     *
     * <p>A rejection returns empty instead of throwing, so the revocation commits.
     */
    @Transactional
    public Optional<Rotation> rotate(String raw) {
        if (raw == null || raw.length() != TOKEN_LENGTH) {
            return Optional.empty();
        }
        byte[] hash = sha256(raw);

        // 1. Which family? (No lock yet; a token's family never changes.)
        Optional<UUID> familyId = jdbc.sql("SELECT family_id FROM refresh_tokens WHERE token_hash = :hash")
                .param("hash", hash)
                .query(UUID.class)
                .optional();
        if (familyId.isEmpty()) {
            return Optional.empty();
        }

        // 2. Lock the family. Concurrent refreshes and logouts of this family queue here. Also
        //    reads the user's current role, so a role change takes effect at the next refresh.
        Family family = lockFamily(familyId.get());
        if (family.revoked()) {
            return Optional.empty();
        }

        // 3. Read the token again, now that we hold the lock: this sees every change committed by
        //    whoever held the lock before us.
        TokenState token = jdbc.sql("""
                        SELECT id, used_at IS NOT NULL AS used, expires_at > now() AS live
                        FROM refresh_tokens WHERE token_hash = :hash
                        """)
                .param("hash", hash)
                .query((rs, n) -> new TokenState(
                        rs.getObject("id", UUID.class), rs.getBoolean("used"), rs.getBoolean("live")))
                .single();

        if (token.used()) {
            // Two parties hold this token: the real user and (probably) a thief. We can't tell
            // which is which, so the whole session ends and both must log in again.
            revokeLockedFamily(family.id(), "REUSE");
            log.warn("Refresh token reuse detected: user={} family={}", family.userId(), family.id());
            return Optional.empty();
        }
        if (!token.live()) {
            return Optional.empty();
        }

        // 4. Consume the token and issue its child.
        jdbc.sql("UPDATE refresh_tokens SET used_at = now() WHERE id = :id")
                .param("id", token.id())
                .update();
        NewToken next = insertToken(family.id());
        jdbc.sql("UPDATE refresh_tokens SET replaced_by = :nextId WHERE id = :id")
                .param("nextId", next.id())
                .param("id", token.id())
                .update();

        return Optional.of(new Rotation(family.userId(), family.role(), next.issued()));
    }

    /** Revokes the family of {@code raw}, if it is one of ours. Idempotent. */
    @Transactional
    public void revokeFamilyOf(String raw) {
        if (raw == null || raw.length() != TOKEN_LENGTH) {
            return;
        }
        jdbc.sql("SELECT family_id FROM refresh_tokens WHERE token_hash = :hash")
                .param("hash", sha256(raw))
                .query(UUID.class)
                .optional()
                .ifPresent(familyId -> {
                    Family family = lockFamily(familyId);
                    if (!family.revoked()) {
                        revokeLockedFamily(familyId, "LOGOUT");
                    }
                });
    }

    private Family lockFamily(UUID familyId) {
        return jdbc.sql("""
                        SELECT f.id, f.user_id, f.revoked_at IS NOT NULL AS revoked, u.role
                        FROM refresh_token_families f
                        JOIN users u ON u.id = f.user_id
                        WHERE f.id = :familyId
                        FOR UPDATE OF f
                        """)
                .param("familyId", familyId)
                .query((rs, n) -> new Family(
                        rs.getObject("id", UUID.class),
                        rs.getObject("user_id", UUID.class),
                        rs.getBoolean("revoked"),
                        Role.valueOf(rs.getString("role"))))
                .single();
    }

    /** Caller must hold the family lock and have checked it is not already revoked. */
    private void revokeLockedFamily(UUID familyId, String reason) {
        jdbc.sql("""
                        UPDATE refresh_token_families SET revoked_at = now(), revoke_reason = :reason
                        WHERE id = :familyId
                        """)
                .param("reason", reason)
                .param("familyId", familyId)
                .update();
    }

    /** A fresh 7-day token, capped at the family's fixed expiry. */
    private NewToken insertToken(UUID familyId) {
        String raw = newRawToken();
        UUID id = UUID.randomUUID();
        Instant expiresAt = jdbc.sql("""
                        INSERT INTO refresh_tokens (id, family_id, token_hash, expires_at)
                        SELECT :id, f.id, :hash, least(now() + :ttlSeconds * interval '1 second', f.expires_at)
                        FROM refresh_token_families f
                        WHERE f.id = :familyId
                        RETURNING expires_at
                        """)
                .param("id", id)
                .param("hash", sha256(raw))
                .param("ttlSeconds", properties.refreshTokenTtl().toSeconds())
                .param("familyId", familyId)
                .query(OffsetDateTime.class)
                .single()
                .toInstant();
        return new NewToken(id, new IssuedRefreshToken(raw, expiresAt));
    }

    private record NewToken(UUID id, IssuedRefreshToken issued) {
    }

    private record Family(UUID id, UUID userId, boolean revoked, Role role) {
    }

    private record TokenState(UUID id, boolean used, boolean live) {
    }

    private static String newRawToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static byte[] sha256(String raw) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every JVM", e);
        }
    }
}
