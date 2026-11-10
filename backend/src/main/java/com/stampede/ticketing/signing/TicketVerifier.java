package com.stampede.ticketing.signing;

import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Checks a ticket QR string the way an offline scanner would (docs/ticket-format.md): with nothing but
 * public keys, looked up by the key id inside the payload. Used by tests now, and by check-in later.
 * <p>
 * Order matters: the signature is checked over the raw payload bytes <b>before</b> any field is
 * trusted. Every failure (malformed text, wrong lengths, unknown format or key, bad signature) is the
 * same empty answer, so a forged code learns nothing about why it failed.
 */
public final class TicketVerifier {

    private static final int SIGNATURE_LENGTH = 64;
    private static final Base64.Encoder BASE64URL = Base64.getUrlEncoder().withoutPadding();

    private final Map<String, PublicKey> keys;

    /** @param rawPublicKeysById raw 32-byte Ed25519 public keys by key id (the JWKS "kid" and "x") */
    public TicketVerifier(Map<String, byte[]> rawPublicKeysById) {
        Map<String, PublicKey> byId = new HashMap<>();
        rawPublicKeysById.forEach((id, raw) -> byId.put(id, Ed25519Keys.publicKeyFromRaw(raw)));
        this.keys = Map.copyOf(byId);
    }

    /** The payload, if {@code qr} is a well-formed ticket signed by one of the known keys. */
    public Optional<TicketPayload> verify(String qr) {
        if (qr == null) {
            return Optional.empty();
        }
        int dot = qr.indexOf('.');
        if (dot < 0 || dot != qr.lastIndexOf('.')) {
            return Optional.empty();
        }
        byte[] payload;
        byte[] signature;
        try {
            payload = Base64.getUrlDecoder().decode(qr.substring(0, dot));
            signature = Base64.getUrlDecoder().decode(qr.substring(dot + 1));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        if (payload.length != TicketPayload.LENGTH || payload[0] != TicketPayload.FORMAT
                || signature.length != SIGNATURE_LENGTH) {
            return Optional.empty();
        }
        // One ticket, one string: Java's decoder also accepts "=" padding and non-zero unused bits in
        // the last character, which would give each ticket many strings that verify. Only the
        // canonical encoding (unpadded, unused bits zero) is accepted, so anything keyed on the raw
        // string (a scanner's dedupe, a cache) sees each ticket once.
        if (!BASE64URL.encodeToString(payload).equals(qr.substring(0, dot))
                || !BASE64URL.encodeToString(signature).equals(qr.substring(dot + 1))) {
            return Optional.empty();
        }
        PublicKey key = keys.get(TicketPayload.keyIdOf(payload));
        if (key == null || !verifySignature(key, payload, signature)) {
            return Optional.empty();
        }
        return Optional.of(TicketPayload.decode(payload));
    }

    static boolean verifySignature(PublicKey key, byte[] message, byte[] signature) {
        try {
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(key);
            verifier.update(message);
            return verifier.verify(signature);
        } catch (GeneralSecurityException e) {
            // A malformed signature can throw SignatureException instead of returning false.
            return false;
        }
    }
}
