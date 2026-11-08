package com.stampede.identity.auth;

import java.util.Optional;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.stampede.identity.token.AccessTokenIssuer;
import com.stampede.identity.token.AccessTokenIssuer.IssuedAccessToken;
import com.stampede.identity.token.RefreshTokenStore;
import com.stampede.identity.token.RefreshTokenStore.IssuedRefreshToken;
import com.stampede.identity.user.Emails;
import com.stampede.identity.user.PasswordPolicy;
import com.stampede.identity.user.UserAccount;
import com.stampede.identity.user.UserRepository;
import com.stampede.shared.error.ApiException;
import com.stampede.shared.error.ErrorCode;
import com.stampede.shared.security.Role;

/** Register, login, refresh and logout. Request validation happens in the web layer. */
@Service
public class AuthService {

    /** What login and refresh hand back: a new access token and a new refresh token. */
    public record TokenPair(IssuedAccessToken access, IssuedRefreshToken refresh) {
    }

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final AccessTokenIssuer accessTokens;
    private final RefreshTokenStore refreshTokens;
    /**
     * A real bcrypt hash of a throwaway value. Login compares against it when the email is
     * unknown, so "no such user" takes as long as "wrong password" and timing doesn't reveal
     * which emails are registered.
     */
    private final String dummyHash;

    public AuthService(UserRepository users, PasswordEncoder passwordEncoder,
                       AccessTokenIssuer accessTokens, RefreshTokenStore refreshTokens) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.accessTokens = accessTokens;
        this.refreshTokens = refreshTokens;
        this.dummyHash = passwordEncoder.encode("timing-equaliser-not-a-real-password");
    }

    public UserAccount register(String email, String password) {
        String hash = passwordEncoder.encode(password);
        return users.insert(Emails.normalize(email), hash, Role.USER)
                .orElseThrow(() -> new ApiException(ErrorCode.EMAIL_TAKEN,
                        "An account with this email already exists."));
    }

    public TokenPair login(String email, String password) {
        // bcrypt rejects inputs over 72 bytes, and no account can have such a password anyway.
        if (PasswordPolicy.exceedsBcryptLimit(password)) {
            throw invalidCredentials();
        }
        Optional<UserAccount> user = users.findByEmail(Emails.normalize(email));
        String hash = user.map(UserAccount::passwordHash).orElse(dummyHash);
        boolean matches = passwordEncoder.matches(password, hash);
        if (user.isEmpty() || !matches) {
            throw invalidCredentials();
        }
        UserAccount account = user.get();
        return new TokenPair(
                accessTokens.issue(account.id(), account.role()),
                refreshTokens.startFamily(account.id()));
    }

    /** Empty when the refresh token is not accepted (the reason is deliberately not exposed). */
    public Optional<TokenPair> refresh(String rawRefreshToken) {
        return refreshTokens.rotate(rawRefreshToken)
                .map(rotation -> new TokenPair(
                        accessTokens.issue(rotation.userId(), rotation.role()),
                        rotation.next()));
    }

    public void logout(String rawRefreshToken) {
        refreshTokens.revokeFamilyOf(rawRefreshToken);
    }

    private static ApiException invalidCredentials() {
        return new ApiException(ErrorCode.INVALID_CREDENTIALS, "Email or password is incorrect.");
    }
}
