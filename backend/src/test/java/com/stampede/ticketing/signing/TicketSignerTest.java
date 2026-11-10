package com.stampede.ticketing.signing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Signing and offline verification (PRD 6.9, D-018): a signed ticket verifies with the public key
 * alone, and any change to it fails.
 */
class TicketSignerTest {

    /** The test key of config/application.properties (32 ASCII bytes). */
    static final byte[] TEST_SEED = "stampede-test-only-ticket-key-00".getBytes(StandardCharsets.US_ASCII);

    private static final Base64.Encoder B64URL = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64URL_DECODER = Base64.getUrlDecoder();

    private final TicketSigner signer = new TicketSigner(TEST_SEED);

    /**
     * The example in docs/ticket-format.md, computed independently of these classes (payload bytes
     * written by hand). A scanner implementation can check itself against it.
     */
    @Test
    void knownAnswerVectorOfTheFormatDocument() {
        TicketPayload payload = new TicketPayload("16ea0a37",
                UUID.fromString("11111111-1111-4111-8111-111111111111"),
                UUID.fromString("22222222-2222-4222-8222-222222222222"),
                UUID.fromString("33333333-3333-4333-8333-333333333333"),
                UUID.fromString("44444444-4444-4444-8444-444444444444"),
                Instant.parse("2026-11-10T12:00:00Z"), 1);

        assertThat(signer.keyId()).isEqualTo("16ea0a37");
        assertThat(B64URL.encodeToString(signer.rawPublicKey())).isEqualTo("s5RRrUy6gk3iu6wVCqS2QU4UGUo3KVPSUhtH_XSiU6s");
        assertThat(java.util.HexFormat.of().formatHex(payload.encode())).isEqualTo("0116ea0a37"
                + "11111111111141118111111111111111" + "22222222222242228222222222222222"
                + "33333333333343338333333333333333" + "44444444444444448444444444444444"
                + "000000006af30740" + "00000001");
        String qr = TicketSigner.qr(payload, signer.sign(payload));
        assertThat(qr).isEqualTo("ARbqCjcRERERERFBEYERERERERERIiIiIiIiQiKCIiIiIiIiIjMzMzMzM0MzgzMzMzMzMzNERERERERERIRERERER"
                + "EREAAAAAGrzB0AAAAAB.5_GwMTIhLuHjOFTr-jOb1j7rXMTRC7Q9vvQm19L6dn_O5UJ4yvrtJ5tus0JFTitX5_BC0Ty6sfy5YeMWACjuCg");
        assertThat(qr).hasSize(195);
        assertThat(signer.verifier().verify(qr)).contains(payload);
    }

    @Test
    void aSignedTicketVerifiesWithOnlyThePublicKey() {
        TicketPayload payload = payload();
        String qr = TicketSigner.qr(payload, signer.sign(payload));

        // Built from the raw public key alone, like a scanner that downloaded GET /tickets/keys.
        TicketVerifier scanner = new TicketVerifier(Map.of(signer.keyId(), signer.rawPublicKey()));

        assertThat(scanner.verify(qr)).contains(payload);
    }

    @Test
    void changingAnySingleBitOfThePayloadFailsVerification() {
        TicketPayload payload = payload();
        byte[] bytes = payload.encode();
        String signature = B64URL.encodeToString(signer.sign(payload));
        TicketVerifier verifier = signer.verifier();
        assertThat(verifier.verify(B64URL.encodeToString(bytes) + "." + signature)).isPresent();

        for (int i = 0; i < bytes.length; i++) {
            for (int bit = 0; bit < 8; bit++) {
                byte[] tampered = bytes.clone();
                tampered[i] ^= (byte) (1 << bit);
                assertThat(verifier.verify(B64URL.encodeToString(tampered) + "." + signature))
                        .as("payload byte %d bit %d flipped", i, bit)
                        .isEmpty();
            }
        }
    }

    @Test
    void changingAnySingleBitOfTheSignatureFailsVerification() {
        TicketPayload payload = payload();
        String encodedPayload = B64URL.encodeToString(payload.encode());
        byte[] signature = signer.sign(payload);
        TicketVerifier verifier = signer.verifier();

        for (int i = 0; i < signature.length; i++) {
            for (int bit = 0; bit < 8; bit++) {
                byte[] tampered = signature.clone();
                tampered[i] ^= (byte) (1 << bit);
                assertThat(verifier.verify(encodedPayload + "." + B64URL.encodeToString(tampered)))
                        .as("signature byte %d bit %d flipped", i, bit)
                        .isEmpty();
            }
        }
    }

    @Test
    void aTicketSignedWithAnotherKeyFailsEvenThoughItNamesOurKeyId() throws Exception {
        // A forger with their own Ed25519 key signs our exact payload bytes (which name OUR key id, so
        // the verifier picks our public key). The signature is well-formed but must not verify.
        byte[] otherSeed = new byte[32];
        otherSeed[0] = 1;
        byte[] bytes = payload().encode();
        Signature forger = Signature.getInstance("Ed25519");
        forger.initSign(Ed25519Keys.fromSeed(otherSeed).getPrivate());
        forger.update(bytes);
        String forged = B64URL.encodeToString(bytes) + "." + B64URL.encodeToString(forger.sign());

        assertThat(signer.verifier().verify(forged)).isEmpty();
    }

    @Test
    void anUnknownKeyIdFailsVerification() {
        TicketPayload payload = payload();
        String qr = TicketSigner.qr(payload, signer.sign(payload));

        assertThat(new TicketVerifier(Map.of()).verify(qr)).isEmpty();
    }

    @Test
    void malformedCodesFailVerification() {
        TicketPayload payload = payload();
        String qr = TicketSigner.qr(payload, signer.sign(payload));
        String[] parts = qr.split("\\.");
        TicketVerifier verifier = signer.verifier();

        assertThat(verifier.verify(null)).isEmpty();
        assertThat(verifier.verify("")).isEmpty();
        assertThat(verifier.verify(parts[0])).as("no signature").isEmpty();
        assertThat(verifier.verify(qr + ".x")).as("three parts").isEmpty();
        assertThat(verifier.verify(parts[0] + ".!!!")).as("not base64url").isEmpty();
        assertThat(verifier.verify(parts[0] + "." + parts[1].substring(4))).as("short signature").isEmpty();
        byte[] otherFormat = B64URL_DECODER.decode(parts[0]);
        otherFormat[0] = 2;
        assertThat(verifier.verify(B64URL.encodeToString(otherFormat) + "." + parts[1])).as("format 2").isEmpty();
    }

    @Test
    void refusesToSignAPayloadNamingAnotherKey() {
        TicketPayload payload = new TicketPayload("00000000", UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), Instant.parse("2026-11-10T12:00:00Z"), 1);

        assertThatThrownBy(() -> signer.sign(payload)).hasMessageContaining("this signer is " + signer.keyId());
    }

    @Test
    void payloadRejectsFractionsOfASecondAndVersionZero() {
        assertThatThrownBy(() -> new TicketPayload(signer.keyId(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), Instant.parse("2026-11-10T12:00:00.5Z"), 1))
                .hasMessageContaining("whole seconds");
        assertThatThrownBy(() -> new TicketPayload(signer.keyId(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), Instant.parse("2026-11-10T12:00:00Z"), 0))
                .hasMessageContaining("at least 1");
    }

    private TicketPayload payload() {
        return new TicketPayload(signer.keyId(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), Instant.parse("2026-11-10T12:00:00Z"), 3);
    }
}
