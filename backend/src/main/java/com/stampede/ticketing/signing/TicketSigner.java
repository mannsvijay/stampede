package com.stampede.ticketing.signing;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.Signature;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;

/**
 * Signs ticket payloads with this instance's Ed25519 key (PRD 6.9, D-018) and turns them into the QR
 * string {@code base64url(payload) "." base64url(signature)} (no padding; docs/ticket-format.md).
 * <p>
 * Ed25519 is deterministic: the same key and payload always give the same 64-byte signature, so there
 * is no random nonce to get wrong. Built once at startup by {@link TicketSigningConfig}; the
 * constructor signs and verifies a test message, so a broken key setup stops the app from starting.
 */
public class TicketSigner {

    private static final Base64.Encoder BASE64URL = Base64.getUrlEncoder().withoutPadding();

    private final KeyPair keys;
    private final byte[] rawPublicKey;
    private final String keyId;

    TicketSigner(byte[] seed) {
        this.keys = Ed25519Keys.fromSeed(seed);
        this.rawPublicKey = Ed25519Keys.rawPublicKey(keys.getPublic());
        this.keyId = keyIdOf(rawPublicKey);
        selfTest();
    }

    /** The 64-byte Ed25519 signature over {@link TicketPayload#encode()}. */
    public byte[] sign(TicketPayload payload) {
        if (!payload.keyId().equals(keyId)) {
            throw new IllegalArgumentException("Payload names key " + payload.keyId() + ", this signer is " + keyId);
        }
        return sign(payload.encode());
    }

    /** The string the QR code encodes, for a payload and the signature stored with it. */
    public static String qr(TicketPayload payload, byte[] signature) {
        return BASE64URL.encodeToString(payload.encode()) + "." + BASE64URL.encodeToString(signature);
    }

    /**
     * This key's id: the first 4 bytes of SHA-256 over the raw public key, as 8 hex characters. Derived,
     * not configured, so it can never disagree with the key, and a new key automatically gets a new id.
     */
    public String keyId() {
        return keyId;
    }

    /** The raw 32-byte public key, for GET /tickets/keys. */
    public byte[] rawPublicKey() {
        return rawPublicKey.clone();
    }

    /** A verifier for this signer's key (today the only key; rotation would add old keys to the map). */
    public TicketVerifier verifier() {
        return new TicketVerifier(Map.of(keyId, rawPublicKey));
    }

    static String keyIdOf(byte[] rawPublicKey) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(rawPublicKey);
            return HexFormat.of().formatHex(hash, 0, 4);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available in the JDK", e);
        }
    }

    private byte[] sign(byte[] message) {
        try {
            Signature signature = Signature.getInstance("Ed25519");
            signature.initSign(keys.getPrivate());
            signature.update(message);
            return signature.sign();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Ed25519 signing failed", e);
        }
    }

    private void selfTest() {
        byte[] message = "stampede ticket signer self-test".getBytes(StandardCharsets.US_ASCII);
        if (!TicketVerifier.verifySignature(Ed25519Keys.publicKeyFromRaw(rawPublicKey), message, sign(message))) {
            throw new IllegalStateException("Ticket signing key self-test failed: the derived public key does not "
                    + "verify the private key's signature");
        }
    }
}
