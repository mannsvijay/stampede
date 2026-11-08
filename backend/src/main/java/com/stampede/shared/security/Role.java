package com.stampede.shared.security;

/** A user's role. Stored in users.role (CHECK constraint in V2) and carried in the access token. */
public enum Role {
    USER,
    ADMIN
}
