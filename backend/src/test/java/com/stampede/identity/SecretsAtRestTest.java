package com.stampede.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.PostgreSQLContainer;

import com.stampede.testsupport.ApiTestSupport;

/**
 * Takes a real pg_dump of the database after a normal session and checks that no password or
 * refresh token is in it, only their hashes. This is what an attacker with a stolen backup sees.
 */
class SecretsAtRestTest extends ApiTestSupport {

    @Autowired
    PostgreSQLContainer<?> postgres;

    @Test
    void databaseDumpContainsNoRawPasswordsOrRefreshTokens() throws Exception {
        String email = uniqueEmail();
        String password = "a-very-distinctive-password-" + System.nanoTime();
        assertThat(register(email, password).status()).isEqualTo(201);

        List<String> refreshTokens = new ArrayList<>();
        String token = login(email, password).refreshCookie();
        refreshTokens.add(token);
        for (int i = 0; i < 2; i++) {
            token = refresh(token).refreshCookie();
            refreshTokens.add(token);
        }

        String dump = pgDump();

        // Sanity check: the dump really contains this user's rows.
        assertThat(dump).contains(email);

        assertThat(dump).doesNotContain(password);
        for (String raw : refreshTokens) {
            assertThat(dump).doesNotContain(raw);
            // Nor the token's raw bytes (pg_dump prints bytea as \x followed by hex).
            assertThat(dump).doesNotContainIgnoringCase(HexFormat.of().formatHex(Base64.getUrlDecoder().decode(raw)));
        }

        String storedHash = jdbc.sql("SELECT password_hash FROM users WHERE email = ?")
                .param(email).query(String.class).single();
        assertThat(storedHash).as("bcrypt, cost 12").startsWith("$2a$12$");
    }

    private String pgDump() throws Exception {
        ExecResult result = postgres.execInContainer(
                "pg_dump", "--data-only", "-U", postgres.getUsername(), postgres.getDatabaseName());
        assertThat(result.getExitCode()).as(result.getStderr()).isZero();
        return result.getStdout();
    }
}
