package com.stampede.identity.security;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

import javax.crypto.SecretKey;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.web.SecurityFilterChain;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.stampede.identity.token.AuthProperties;

/**
 * The HTTP security rules (D-008).
 *
 * <p>Spring's {@code BearerTokenAuthenticationFilter} reads {@code Authorization: Bearer <token>},
 * the {@link JwtDecoder} below checks it (HS256 signature, expiry, issuer), and
 * {@link CurrentUserAuthentication.FromJwt} turns the claims into a {@code CurrentUser}. No session
 * is created: every request carries its own token.
 *
 * <p>Routes of other modules appear here only as path strings, which adds no module dependency.
 */
@Configuration
@EnableConfigurationProperties(AuthProperties.class)
class SecurityConfig {

    static final int BCRYPT_COST = 12;
    private static final Duration CLOCK_SKEW = Duration.ofSeconds(30);

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, JwtDecoder jwtDecoder,
                                    ProblemJsonSecurityHandlers problems) throws Exception {
        http
                // No cookies authenticate API calls, so CSRF tokens aren't needed. The one cookie we
                // have (the refresh token) is protected by SameSite=Strict plus a required custom
                // header on /auth/refresh and /auth/logout (see AuthController).
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST,
                                "/auth/register", "/auth/login", "/auth/refresh", "/auth/logout").permitAll()
                        .requestMatchers(HttpMethod.GET, "/events", "/events/*").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        // Spring forwards unhandled errors to /error; without this, they'd turn into 401s.
                        .requestMatchers("/error").permitAll()
                        .requestMatchers("/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(jwt -> jwt
                                .decoder(jwtDecoder)
                                .jwtAuthenticationConverter(new CurrentUserAuthentication.FromJwt()))
                        .authenticationEntryPoint(problems)
                        .accessDeniedHandler(problems))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(problems)
                        .accessDeniedHandler(problems));
        return http.build();
    }

    /**
     * Accepts only HS256 tokens signed with our key: a token claiming {@code alg: none} or any
     * other algorithm is rejected. Also requires our issuer and an {@code exp} claim, and allows
     * 30 seconds of clock skew between instances.
     */
    @Bean
    JwtDecoder jwtDecoder(AuthProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(properties.signingKey())
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(CLOCK_SKEW),
                new JwtIssuerValidator(properties.issuer()),
                // JwtTimestampValidator skips tokens without exp; we never issue those.
                new JwtClaimValidator<Instant>(JwtClaimNames.EXP, Objects::nonNull)));
        return decoder;
    }

    @Bean
    JwtEncoder jwtEncoder(AuthProperties properties) {
        SecretKey key = properties.signingKey();
        return new NimbusJwtEncoder(new ImmutableSecret<>(key));
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(BCRYPT_COST);
    }
}
