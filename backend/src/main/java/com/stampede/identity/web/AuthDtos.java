package com.stampede.identity.web;

import java.util.UUID;

import com.stampede.identity.user.UserAccount;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Request and response bodies of the /auth endpoints. */
final class AuthDtos {

    private AuthDtos() {
    }

    record RegisterRequest(
            @NotBlank @Email @Size(max = 254) String email,
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
