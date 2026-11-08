package com.stampede.identity.security;

import java.util.List;
import java.util.UUID;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;

import com.stampede.identity.token.AccessTokenIssuer;
import com.stampede.shared.security.CurrentUser;
import com.stampede.shared.security.Role;

/**
 * The authentication stored in the security context for a request with a valid access token.
 * Its principal is a {@link CurrentUser}, so controllers in any module can take
 * {@code @AuthenticationPrincipal CurrentUser} without knowing about JWTs.
 */
final class CurrentUserAuthentication extends AbstractAuthenticationToken {

    private final CurrentUser user;
    private final Jwt jwt;

    private CurrentUserAuthentication(CurrentUser user, Jwt jwt) {
        // "ROLE_ADMIN" is what hasRole("ADMIN") checks for.
        super(List.of(new SimpleGrantedAuthority("ROLE_" + user.role().name())));
        this.user = user;
        this.jwt = jwt;
        setAuthenticated(true);
    }

    @Override
    public CurrentUser getPrincipal() {
        return user;
    }

    @Override
    public Jwt getCredentials() {
        return jwt;
    }

    @Override
    public String getName() {
        return user.userId().toString();
    }

    /**
     * Runs after the signature, expiry and issuer checks have passed. A signed token with bad
     * claims can only come from a bug on our side; it is still rejected as an invalid token (401).
     */
    static final class FromJwt implements Converter<Jwt, AbstractAuthenticationToken> {

        @Override
        public AbstractAuthenticationToken convert(Jwt jwt) {
            try {
                UUID userId = UUID.fromString(jwt.getSubject());
                Role role = Role.valueOf(jwt.getClaimAsString(AccessTokenIssuer.ROLE_CLAIM));
                return new CurrentUserAuthentication(new CurrentUser(userId, role), jwt);
            } catch (RuntimeException e) {
                throw new InvalidBearerTokenException("Access token has malformed claims");
            }
        }
    }
}
