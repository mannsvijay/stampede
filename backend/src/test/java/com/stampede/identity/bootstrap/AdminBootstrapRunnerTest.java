package com.stampede.identity.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.stampede.identity.bootstrap.AdminBootstrapRunner.Outcome;
import com.stampede.identity.user.UserRepository;
import com.stampede.testsupport.ApiTestSupport;

/**
 * The runner only exists under the bootstrap-admin profile, so these tests construct it directly
 * with the real repository and encoder, using the shared test database.
 */
class AdminBootstrapRunnerTest extends ApiTestSupport {

    @Autowired
    UserRepository users;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Test
    void createsAnAdminWhoCanLogInAndUseAdminRoutes() {
        String email = uniqueEmail();

        assertThat(runner(email, PASSWORD).bootstrap()).isEqualTo(Outcome.CREATED);

        Response login = login(email, PASSWORD);
        assertThat(login.status()).isEqualTo(200);
        assertThat(get("/admin/_probe", login.accessToken()).status()).isEqualTo(200);
    }

    @Test
    void isIdempotent() {
        String email = uniqueEmail();
        runner(email, PASSWORD).bootstrap();

        assertThat(runner(email, PASSWORD).bootstrap()).isEqualTo(Outcome.ALREADY_ADMIN);
        assertThat(jdbc.sql("SELECT count(*) FROM users WHERE email = ?")
                .param(email).query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void promotesAnExistingUserWithoutChangingTheirPassword() {
        String email = uniqueEmail();
        register(email, PASSWORD);

        assertThat(runner(email.toUpperCase(), "some-other-password-123").bootstrap()).isEqualTo(Outcome.PROMOTED);

        assertThat(login(email, "some-other-password-123").status()).isEqualTo(401);
        Response login = login(email, PASSWORD);
        assertThat(get("/admin/_probe", login.accessToken()).status()).isEqualTo(200);
    }

    @Test
    void refusesMissingSettings() {
        assertThatThrownBy(() -> runner("", PASSWORD).bootstrap())
                .hasMessageContaining("STAMPEDE_ADMIN_EMAIL is not set");
        assertThatThrownBy(() -> runner(uniqueEmail(), "").bootstrap())
                .hasMessageContaining("STAMPEDE_ADMIN_PASSWORD is not set");
    }

    @Test
    void refusesAWeakPassword() {
        String email = uniqueEmail();

        assertThatThrownBy(() -> runner(email, "short").bootstrap())
                .hasMessageContaining("must be at least 12 characters");
        assertThat(users.findByEmail(email)).isEmpty();
    }

    private AdminBootstrapRunner runner(String email, String password) {
        return new AdminBootstrapRunner(users, passwordEncoder, email, password);
    }
}
