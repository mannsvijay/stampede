package com.stampede.identity.web;

import java.util.UUID;

import com.stampede.identity.user.PasswordPolicy;
import com.stampede.identity.user.UserAccount;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Request and response bodies of the /auth endpoints. */
final class AuthDtos {

    private AuthDtos() {
    }

    /**
     * The {@code @Schema} on password is documentation only: {@link ValidPassword} already rejects null
     * at runtime, but springdoc can't see that from a custom constraint. JSON Schema's minLength counts
     * code points, like PasswordPolicy; the 72-byte cap can't be expressed, so it's in the description.
     */
    record RegisterRequest(
            @NotBlank @Email @Size(max = 254) String email,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minLength = PasswordPolicy.MIN_CHARACTERS,
                    description = "At least 12 characters and at most 72 UTF-8 bytes")
            @ValidPassword String password) {
    }

    /** No password policy here: login must not reveal the rules, it only checks the hash. */
    record LoginRequest(
            @NotBlank @Size(max = 254) String email,
            @NotBlank String password) {
    }

    record UserResponse(UUID id, String email, String role) {
        static UserResponse of(UserAccount user) {
            return new UserResponse(user.id(), user.email(), user.role().name());
        }
    }

    /** The refresh token is not in the body: it travels only in the httpOnly cookie. */
    record TokenResponse(String accessToken, String tokenType, long expiresIn) {
    }
}
