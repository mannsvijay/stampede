package com.stampede.identity.bootstrap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import com.stampede.identity.user.Emails;
import com.stampede.identity.user.PasswordPolicy;
import com.stampede.identity.user.UserAccount;
import com.stampede.identity.user.UserRepository;
import com.stampede.shared.security.Role;

/**
 * Creates the first ADMIN in local development, from STAMPEDE_ADMIN_EMAIL and STAMPEDE_ADMIN_PASSWORD
 * (the gitignored .env), so no password is ever committed.
 *
 * <p>Run: {@code cd backend && ./gradlew bootRun --args='--spring.profiles.active=bootstrap-admin'}
 *
 * <p>Idempotent: creates the user if missing, promotes an existing user to ADMIN, and never changes
 * an existing user's password. Any problem stops startup, so it can't fail silently.
 */
@Component
@Profile("bootstrap-admin")
class AdminBootstrapRunner implements ApplicationRunner {

    enum Outcome { CREATED, PROMOTED, ALREADY_ADMIN }

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrapRunner.class);

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final String email;
    private final String password;

    AdminBootstrapRunner(UserRepository users, PasswordEncoder passwordEncoder,
                         @Value("${stampede.bootstrap-admin.email:}") String email,
                         @Value("${stampede.bootstrap-admin.password:}") String password) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.email = email;
        this.password = password;
    }

    @Override
    public void run(ApplicationArguments args) {
        Outcome outcome = bootstrap();
        log.info("bootstrap-admin: {} {}", Emails.normalize(email), outcome);
    }

    Outcome bootstrap() {
        if (email == null || email.isBlank()) {
            throw new IllegalStateException("bootstrap-admin: STAMPEDE_ADMIN_EMAIL is not set");
        }
        if (password == null || password.isBlank()) {
            throw new IllegalStateException("bootstrap-admin: STAMPEDE_ADMIN_PASSWORD is not set");
        }
        PasswordPolicy.violation(password).ifPresent(problem -> {
            throw new IllegalStateException("bootstrap-admin: STAMPEDE_ADMIN_PASSWORD " + problem);
        });

        String normalized = Emails.normalize(email);
        var existing = users.findByEmail(normalized);
        if (existing.isPresent()) {
            return promote(existing.get());
        }
        return users.insert(normalized, passwordEncoder.encode(password), Role.ADMIN)
                .map(created -> Outcome.CREATED)
                // Someone registered this email between our lookup and insert.
                .orElseGet(() -> promote(users.findByEmail(normalized).orElseThrow()));
    }

    private Outcome promote(UserAccount user) {
        if (user.role() == Role.ADMIN) {
            return Outcome.ALREADY_ADMIN;
        }
        users.promoteToAdmin(user.email());
        return Outcome.PROMOTED;
    }
}
