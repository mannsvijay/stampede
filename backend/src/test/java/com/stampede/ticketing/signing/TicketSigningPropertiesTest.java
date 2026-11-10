package com.stampede.ticketing.signing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** The app must refuse to start without a valid ticket signing key (D-018). */
class TicketSigningPropertiesTest {

    private static final String VALID_KEY = Base64.getEncoder().encodeToString(new byte[32]);

    @Test
    void acceptsA32ByteKey() {
        assertThat(new TicketSigningProperties(VALID_KEY).seed()).hasSize(32);
    }

    @Test
    void rejectsAMissingKey() {
        assertThatThrownBy(() -> new TicketSigningProperties(null)).hasMessageContaining("STAMPEDE_TICKET_SIGNING_KEY");
        assertThatThrownBy(() -> new TicketSigningProperties(" ")).hasMessageContaining("STAMPEDE_TICKET_SIGNING_KEY");
    }

    @Test
    void rejectsTheEnvExamplePlaceholder() {
        assertThatThrownBy(() -> new TicketSigningProperties("change-me-generate-32-random-bytes-base64"))
                .hasMessageContaining("not valid base64");
    }

    @Test
    void rejectsAnyLengthButExactly32Bytes() {
        String short31 = Base64.getEncoder().encodeToString(new byte[31]);
        String long33 = Base64.getEncoder().encodeToString(new byte[33]);

        assertThatThrownBy(() -> new TicketSigningProperties(short31)).hasMessageContaining("decodes to 31 bytes");
        assertThatThrownBy(() -> new TicketSigningProperties(long33)).hasMessageContaining("decodes to 33 bytes");
    }

    @Test
    void neverPrintsTheKey() {
        assertThat(new TicketSigningProperties(VALID_KEY).toString()).doesNotContain(VALID_KEY);
    }

    @Test
    void applicationContextFailsToStartWithoutAKey() {
        new ApplicationContextRunner()
                .withUserConfiguration(TicketSigningConfig.class)
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("STAMPEDE_TICKET_SIGNING_KEY"));
    }

    @Test
    void applicationContextStartsWithAValidKeyAndBuildsTheSigner() {
        new ApplicationContextRunner()
                .withUserConfiguration(TicketSigningConfig.class)
                .withPropertyValues("stampede.tickets.signing-key=" + VALID_KEY)
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(TicketSigner.class));
    }
}
