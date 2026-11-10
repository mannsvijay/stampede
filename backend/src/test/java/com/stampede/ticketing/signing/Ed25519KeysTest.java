package com.stampede.ticketing.signing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.KeyPair;
import java.security.Signature;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

/**
 * The seed-to-key-pair workaround against RFC 8032's published test vector. If a future JDK's
 * generator stops using the seed the way {@link Ed25519Keys} expects, this fails (and so does startup).
 */
class Ed25519KeysTest {

    private static final HexFormat HEX = HexFormat.of();

    // RFC 8032, section 7.1, TEST 1 (empty message).
    private static final byte[] SEED = HEX.parseHex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60");
    private static final String PUBLIC_KEY = "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a";
    private static final String SIGNATURE = "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e06522490155"
            + "5fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b";

    @Test
    void derivesTheRfc8032PublicKeyFromTheSeed() {
        KeyPair pair = Ed25519Keys.fromSeed(SEED);

        assertThat(HEX.formatHex(Ed25519Keys.rawPublicKey(pair.getPublic()))).isEqualTo(PUBLIC_KEY);
    }

    @Test
    void signsLikeRfc8032() throws Exception {
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(Ed25519Keys.fromSeed(SEED).getPrivate());
        signer.update(new byte[0]);

        assertThat(HEX.formatHex(signer.sign())).isEqualTo(SIGNATURE);
    }

    @Test
    void rawPublicKeyRoundTrips() {
        byte[] raw = HEX.parseHex(PUBLIC_KEY);

        assertThat(Ed25519Keys.rawPublicKey(Ed25519Keys.publicKeyFromRaw(raw))).isEqualTo(raw);
    }

    @Test
    void leavesTheCallersSeedUntouched() {
        byte[] seed = SEED.clone();

        Ed25519Keys.fromSeed(seed);

        assertThat(seed).isEqualTo(SEED);
    }

    @Test
    void rejectsSeedsOfTheWrongLength() {
        assertThatThrownBy(() -> Ed25519Keys.fromSeed(new byte[31])).hasMessageContaining("32 bytes, got 31");
        assertThatThrownBy(() -> Ed25519Keys.fromSeed(new byte[33])).hasMessageContaining("32 bytes, got 33");
    }
}
