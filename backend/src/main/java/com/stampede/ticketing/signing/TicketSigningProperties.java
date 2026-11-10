package com.stampede.ticketing.signing;

import java.util.Base64;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Ticket signing settings ({@code stampede.tickets.*}). The constructor validates them, so a missing or
 * malformed key stops the application at startup instead of failing at the first sale (D-018).
 *
 * @param signingKey base64 of the 32-byte Ed25519 seed (the private key), from
 *                   {@code STAMPEDE_TICKET_SIGNING_KEY}
 */
@ConfigurationProperties("stampede.tickets")
public record TicketSigningProperties(String signingKey) {

    private static final String HOW_TO = "Generate one (base64 of 32 random bytes) as described in docs/DEVELOPING.md, "
            + "or with: openssl rand -base64 32";

    public TicketSigningProperties {
        if (signingKey == null || signingKey.isBlank()) {
            throw new IllegalArgumentException("stampede.tickets.signing-key is not set. "
                    + "Set STAMPEDE_TICKET_SIGNING_KEY. " + HOW_TO);
        }
        byte[] seed;
        try {
            seed = Base64.getDecoder().decode(signingKey.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("STAMPEDE_TICKET_SIGNING_KEY is not valid base64. " + HOW_TO);
        }
        if (seed.length != Ed25519Keys.SEED_BYTES) {
            // Exactly 32: an Ed25519 private key has no other size (unlike an HMAC secret, longer isn't stronger).
            throw new IllegalArgumentException("STAMPEDE_TICKET_SIGNING_KEY decodes to " + seed.length
                    + " bytes; an Ed25519 key is exactly " + Ed25519Keys.SEED_BYTES + ". " + HOW_TO);
        }
    }

    byte[] seed() {
        return Base64.getDecoder().decode(signingKey.trim());
    }

    /** Never print the key, even in a startup failure report. */
    @Override
    public String toString() {
        return "TicketSigningProperties[signingKey=<redacted>]";
    }
}
