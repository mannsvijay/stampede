package com.stampede.shared.security;

import java.util.Objects;
import java.util.UUID;

/**
 * The authenticated caller of the current request, built from a verified access token.
 * Controllers receive it with {@code @AuthenticationPrincipal CurrentUser user}.
 */
public record CurrentUser(UUID userId, Role role) {

    public CurrentUser {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(role, "role");
    }

    public boolean isAdmin() {
        return role == Role.ADMIN;
    }
}
