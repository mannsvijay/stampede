package com.stampede.identity.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Base64;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/** The app must refuse to start without a usable signing key. */
class AuthPropertiesTest {

    private static final String VALID_SECRET = Base64.getEncoder().encodeToString(new byte[32]);

    @Test
    void acceptsA32ByteKey() {
        assertThat(properties(VALID_SECRET).signingKey().getEncoded()).hasSize(32);
    }

    @Test
    void rejectsMissingSecret() {
        assertThatThrownBy(() -> properties(null)).hasMessageContaining("STAMPEDE_JWT_SECRET");
        assertThatThrownBy(() -> properties("  ")).hasMessageContaining("STAMPEDE_JWT_SECRET");
    }

    @Test
    void rejectsTheEnvExamplePlaceholder() {
        assertThatThrownBy(() -> properties("change-me-run-openssl-rand-base64-32"))
                .hasMessageContaining("not valid base64");
    }

    @Test
    void rejectsAKeyShorterThan32Bytes() {
        String short31 = Base64.getEncoder().encodeToString(new byte[31]);

        assertThatThrownBy(() -> properties(short31)).hasMessageContaining("decodes to 31 bytes");
    }

    @Test
    void rejectsARefreshTtlLongerThanTheFamilyCap() {
        assertThatThrownBy(() -> new AuthProperties(VALID_SECRET, "stampede",
                Duration.ofMinutes(15), Duration.ofDays(31), Duration.ofDays(30)))
                .hasMessageContaining("must not exceed");
    }

    @Test
    void applicationContextFailsToStartWithoutASecret() {
        new ApplicationContextRunner()
                .withUserConfiguration(Config.class)
                .withPropertyValues("stampede.auth.issuer=stampede", "stampede.auth.access-token-ttl=15m",
                        "stampede.auth.refresh-token-ttl=7d", "stampede.auth.refresh-family-max-age=30d")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("STAMPEDE_JWT_SECRET"));
    }

    private static AuthProperties properties(String secret) {
        return new AuthProperties(secret, "stampede",
                Duration.ofMinutes(15), Duration.ofDays(7), Duration.ofDays(30));
    }

    @Configuration
    @EnableConfigurationProperties(AuthProperties.class)
    static class Config {
    }
}
