package com.stampede.identity.user;

import java.util.UUID;

import com.stampede.shared.security.Role;

/** A row of the users table. The email is always stored normalised (see {@link Emails}). */
public record UserAccount(UUID id, String email, String passwordHash, Role role) {
}
