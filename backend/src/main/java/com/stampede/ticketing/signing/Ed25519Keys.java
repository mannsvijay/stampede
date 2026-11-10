package com.stampede.ticketing.signing;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.interfaces.EdECPrivateKey;
import java.security.spec.NamedParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.HexFormat;

/**
 * Ed25519 key handling with only the JDK (D-018, option A). This is the one place with a workaround.
 *
 * <h2>The problem</h2>
 * An Ed25519 private key is just 32 random bytes, the <i>seed</i>. The public key is computed from the
 * seed. We keep only the seed in the environment ({@code STAMPEDE_TICKET_SIGNING_KEY}), because 32
 * random bytes are easy to generate anywhere, even in PowerShell, which can't make Ed25519 keys. But
 * the JDK (21) has no public method for "seed in, public key out".
 *
 * <h2>The workaround, in plain words</h2>
 * The JDK's {@link KeyPairGenerator} makes a new key pair by asking a {@link SecureRandom} for 32
 * random bytes, using them as the seed, and computing the public key from them. So we give it a
 * "random" source that isn't random: it answers that one request with <b>our</b> seed. The generator
 * then computes the public key for our seed, which is exactly what we wanted.
 *
 * <h2>Why it is safe to rely on</h2>
 * It depends on how the JDK generator uses its random source, which no specification promises. So
 * {@link #fromSeed} checks the result instead of trusting it: the private key it got back must contain
 * our seed byte for byte, and the random source refuses any request other than one of exactly 32 bytes.
 * {@code TicketKeys} also signs and verifies a test message at startup. If a future JDK works
 * differently, the app refuses to start instead of signing with a wrong key, and
 * {@code Ed25519KeysTest} fails against the RFC 8032 test vector.
 *
 * <h2>Raw public keys</h2>
 * Java encodes public keys in the standard X.509 "SubjectPublicKeyInfo" form: for Ed25519 always the
 * same 12-byte header followed by the 32 raw key bytes. Scanners and JWKS documents use the raw 32
 * bytes, so {@link #rawPublicKey} strips the header and {@link #publicKeyFromRaw} adds it back.
 */
final class Ed25519Keys {

    static final int SEED_BYTES = 32;
    static final int RAW_PUBLIC_KEY_BYTES = 32;

    /** DER header of an Ed25519 SubjectPublicKeyInfo (RFC 8410): SEQUENCE, algorithm OID 1.3.101.112, BIT STRING. */
    private static final byte[] X509_HEADER = HexFormat.of().parseHex("302a300506032b6570032100");

    private Ed25519Keys() {
    }

    /** The key pair for a 32-byte seed. Does not modify {@code seed}. */
    static KeyPair fromSeed(byte[] seed) {
        if (seed.length != SEED_BYTES) {
            throw new IllegalArgumentException("An Ed25519 seed is " + SEED_BYTES + " bytes, got " + seed.length);
        }
        KeyPair pair;
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
            generator.initialize(NamedParameterSpec.ED25519, new SeedAsRandom(seed));
            pair = generator.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("This JDK cannot generate Ed25519 keys", e);
        }
        // Did the generator really use our seed? The private key carries it.
        byte[] used = ((EdECPrivateKey) pair.getPrivate()).getBytes()
                .orElseThrow(() -> new IllegalStateException("The JDK's Ed25519 private key does not expose its seed"));
        if (!MessageDigest.isEqual(used, seed)) {
            throw new IllegalStateException("The JDK's Ed25519 generator did not use the given seed");
        }
        return pair;
    }

    /** The 32 raw bytes of an Ed25519 public key (what JWKS "x" and scanners use). */
    static byte[] rawPublicKey(PublicKey key) {
        byte[] encoded = key.getEncoded();
        if (encoded.length != X509_HEADER.length + RAW_PUBLIC_KEY_BYTES
                || !Arrays.equals(encoded, 0, X509_HEADER.length, X509_HEADER, 0, X509_HEADER.length)) {
            throw new IllegalArgumentException("Not an Ed25519 public key");
        }
        return Arrays.copyOfRange(encoded, X509_HEADER.length, encoded.length);
    }

    /** A public key from its 32 raw bytes. */
    static PublicKey publicKeyFromRaw(byte[] raw) {
        if (raw.length != RAW_PUBLIC_KEY_BYTES) {
            throw new IllegalArgumentException("An Ed25519 public key is " + RAW_PUBLIC_KEY_BYTES + " bytes, got "
                    + raw.length);
        }
        byte[] encoded = Arrays.copyOf(X509_HEADER, X509_HEADER.length + raw.length);
        System.arraycopy(raw, 0, encoded, X509_HEADER.length, raw.length);
        try {
            return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(encoded));
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("Not a valid Ed25519 public key", e);
        }
    }

    /**
     * A "random" source that hands out the seed once, as the only 32 bytes it will ever give. Any other
     * request means the JDK generator works differently from what {@link #fromSeed} assumes, so it fails
     * loudly rather than mixing real randomness into the key.
     */
    private static final class SeedAsRandom extends SecureRandom {

        private byte[] seed;

        SeedAsRandom(byte[] seed) {
            this.seed = seed.clone();
        }

        @Override
        public void nextBytes(byte[] bytes) {
            if (seed == null || bytes.length != seed.length) {
                throw new IllegalStateException("Unexpected request for " + bytes.length
                        + " random bytes while deriving an Ed25519 key from a seed");
            }
            System.arraycopy(seed, 0, bytes, 0, seed.length);
            Arrays.fill(seed, (byte) 0);
            seed = null;
        }
    }
}
