package com.stampede.ticketing.signing;

import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Base64;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * GET /tickets/keys: the public keys a scanner needs to verify tickets offline (D-018). Public (in
 * SecurityConfig's permitAll list): a public key is meant to be shared, and scanners download it
 * before going offline.
 * <p>
 * The response is a standard JSON Web Key Set (RFC 7517), with Ed25519 keys in the OKP form of
 * RFC 8037: {@code x} is the raw 32-byte public key in base64url, {@code kid} matches the key id
 * bytes in every ticket payload. A list, so a rotation can publish the new key next to the old one.
 */
@Tag(name = "tickets")
@RestController
class TicketKeysController {

    record Jwk(
            @Schema(example = "OKP") String kty,
            @Schema(example = "Ed25519") String crv,
            @Schema(description = "Key id: hex of the first 4 bytes of SHA-256 over the raw public key",
                    example = "1a2b3c4d") String kid,
            @Schema(description = "The raw 32-byte Ed25519 public key, base64url without padding") String x,
            @Schema(example = "EdDSA") String alg,
            @Schema(example = "sig") String use) {
    }

    record KeySet(List<Jwk> keys) {
    }

    private final TicketSigner signer;

    TicketKeysController(TicketSigner signer) {
        this.signer = signer;
    }

    @GetMapping("/tickets/keys")
    @ApiResponse(responseCode = "200", description = "The keys that sign tickets (JWKS, RFC 7517/8037)",
            content = @Content(schema = @Schema(implementation = KeySet.class)))
    KeySet ticketKeys() {
        String x = Base64.getUrlEncoder().withoutPadding().encodeToString(signer.rawPublicKey());
        return new KeySet(List.of(new Jwk("OKP", "Ed25519", signer.keyId(), x, "EdDSA", "sig")));
    }
}
