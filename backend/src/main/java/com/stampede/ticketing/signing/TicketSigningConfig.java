package com.stampede.ticketing.signing;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Builds the {@link TicketSigner} from {@code STAMPEDE_TICKET_SIGNING_KEY}. A missing or invalid key
 * fails here (in {@link TicketSigningProperties}, or in the signer's self-test), so the app won't start.
 */
@Configuration
@EnableConfigurationProperties(TicketSigningProperties.class)
class TicketSigningConfig {

    @Bean
    TicketSigner ticketSigner(TicketSigningProperties properties) {
        return new TicketSigner(properties.seed());
    }
}
