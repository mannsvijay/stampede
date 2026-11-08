package com.stampede.identity.web;

import java.time.Duration;
import java.time.Instant;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.stampede.identity.auth.AuthService;
import com.stampede.identity.auth.AuthService.TokenPair;
import com.stampede.identity.web.AuthDtos.LoginRequest;
import com.stampede.identity.web.AuthDtos.RegisterRequest;
import com.stampede.identity.web.AuthDtos.TokenResponse;
import com.stampede.identity.web.AuthDtos.UserResponse;
import com.stampede.shared.error.ApiException;
import com.stampede.shared.error.ErrorCode;
import com.stampede.shared.error.ErrorResponses;
import com.stampede.shared.error.Problems;

import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/**
 * POST /auth/register, /auth/login, /auth/refresh, /auth/logout (D-008).
 *
 * <p>The access token goes in the response body; the client keeps it in memory. The refresh token
 * goes only in an httpOnly cookie scoped to /auth, so page JavaScript (and an XSS payload) can't
 * read it.
 *
 * <p>CSRF: the browser attaches the cookie automatically, so refresh and logout also require the
 * {@value #CLIENT_HEADER} header. A cross-site form can't set custom headers, and a cross-site
 * fetch that sets one needs a CORS preflight, which this API doesn't allow. SameSite=Strict
 * already stops the cookie being sent cross-site; the header is a second, independent guard.
 */
@Tag(name = "auth")
@RestController
@RequestMapping("/auth")
class AuthController {

    static final String REFRESH_COOKIE = "stampede_refresh";
    static final String CLIENT_HEADER = "X-Requested-With";

    private final AuthService auth;

    AuthController(AuthService auth) {
        this.auth = auth;
    }

    @PostMapping("/register")
    @ApiResponse(responseCode = "201", description = "Registered",
            content = @Content(schema = @Schema(implementation = UserResponse.class)))
    @ErrorResponses({ErrorCode.VALIDATION_FAILED, ErrorCode.EMAIL_TAKEN})
    ResponseEntity<UserResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(UserResponse.of(auth.register(request.email(), request.password())));
    }

    @PostMapping("/login")
    @ApiResponse(responseCode = "200", description = "Logged in; the refresh token is set as an httpOnly cookie",
            content = @Content(schema = @Schema(implementation = TokenResponse.class)))
    @ErrorResponses({ErrorCode.VALIDATION_FAILED, ErrorCode.INVALID_CREDENTIALS})
    ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest request) {
        return tokens(auth.login(request.email(), request.password()));
    }

    @PostMapping("/refresh")
    @ApiResponse(responseCode = "200", description = "Rotated; the new refresh token is set as an httpOnly cookie",
            content = @Content(schema = @Schema(implementation = TokenResponse.class)))
    @ErrorResponses({ErrorCode.INVALID_REFRESH_TOKEN, ErrorCode.FORBIDDEN})
    ResponseEntity<?> refresh(
            @RequestHeader(name = CLIENT_HEADER, required = false) String clientHeader,
            @CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken) {
        requireClientHeader(clientHeader);
        return auth.refresh(refreshToken)
                .<ResponseEntity<?>>map(this::tokens)
                // Clear the dead cookie so the client stops sending it.
                .orElseGet(() -> ResponseEntity.status(ErrorCode.INVALID_REFRESH_TOKEN.status())
                        .header(HttpHeaders.SET_COOKIE, clearedCookie().toString())
                        .body(Problems.of(ErrorCode.INVALID_REFRESH_TOKEN,
                                "The refresh token is missing, expired or no longer valid. Log in again.")));
    }

    /** Always 204, even without a cookie, so logging out twice is harmless. */
    @PostMapping("/logout")
    @ApiResponse(responseCode = "204", description = "Logged out; the refresh cookie is cleared")
    @ErrorResponses(ErrorCode.FORBIDDEN)
    ResponseEntity<Void> logout(
            @RequestHeader(name = CLIENT_HEADER, required = false) String clientHeader,
            @CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken) {
        requireClientHeader(clientHeader);
        auth.logout(refreshToken);
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, clearedCookie().toString())
                .build();
    }

    private ResponseEntity<TokenResponse> tokens(TokenPair pair) {
        Duration maxAge = Duration.between(Instant.now(), pair.refresh().expiresAt());
        ResponseCookie cookie = refreshCookie(pair.refresh().value(), maxAge);
        TokenResponse body = new TokenResponse(
                pair.access().value(), "Bearer", pair.access().expiresIn().toSeconds());
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(body);
    }

    private static void requireClientHeader(String value) {
        if (value == null || value.isBlank()) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Missing " + CLIENT_HEADER + " header.");
        }
    }

    /**
     * Browsers accept Secure cookies from http://localhost, so Secure is on in development too.
     */
    private static ResponseCookie refreshCookie(String value, Duration maxAge) {
        return ResponseCookie.from(REFRESH_COOKIE, value)
                .httpOnly(true)
                .secure(true)
                .sameSite("Strict")
                .path("/auth")
                .maxAge(maxAge)
                .build();
    }

    private static ResponseCookie clearedCookie() {
        return refreshCookie("", Duration.ZERO);
    }
}
