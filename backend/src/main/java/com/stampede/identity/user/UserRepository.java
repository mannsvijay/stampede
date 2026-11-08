package com.stampede.identity.user;

import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.stampede.shared.security.Role;

@Repository
public class UserRepository {

    private static final RowMapper<UserAccount> ROW = (rs, n) -> new UserAccount(
            rs.getObject("id", UUID.class),
            rs.getString("email"),
            rs.getString("password_hash"),
            Role.valueOf(rs.getString("role")));

    private final JdbcClient jdbc;

    public UserRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** {@code normalizedEmail} must come from {@link Emails#normalize}. */
    public Optional<UserAccount> findByEmail(String normalizedEmail) {
        return jdbc.sql("""
                        SELECT id, email, password_hash, role FROM users
                        WHERE lower(email) = :email
                        """)
                .param("email", normalizedEmail)
                .query(ROW)
                .optional();
    }

    /**
     * Inserts the user, or returns empty if the email is already taken. ON CONFLICT on the
     * lower(email) unique index makes this race-free: of two concurrent registrations for the same
     * email, exactly one inserts and the other gets empty, with no exception to catch.
     */
    public Optional<UserAccount> insert(String normalizedEmail, String passwordHash, Role role) {
        return jdbc.sql("""
                        INSERT INTO users (id, email, password_hash, role)
                        VALUES (:id, :email, :passwordHash, :role)
                        ON CONFLICT ((lower(email))) DO NOTHING
                        RETURNING id, email, password_hash, role
                        """)
                .param("id", UUID.randomUUID())
                .param("email", normalizedEmail)
                .param("passwordHash", passwordHash)
                .param("role", role.name())
                .query(ROW)
                .optional();
    }

    /** Returns true if the user existed and was not already an ADMIN. */
    public boolean promoteToAdmin(String normalizedEmail) {
        return jdbc.sql("UPDATE users SET role = 'ADMIN' WHERE lower(email) = :email AND role <> 'ADMIN'")
                .param("email", normalizedEmail)
                .update() == 1;
    }
}
