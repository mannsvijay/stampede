package com.stampede.identity.user;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Password rules (D-008): at least 12 characters, no composition rules (NIST SP 800-63B), and at
 * most 72 bytes of UTF-8, because bcrypt ignores everything after byte 72. Without the cap, two
 * passwords sharing their first 72 bytes would both be accepted.
 */
public final class PasswordPolicy {

    public static final int MIN_CHARACTERS = 12;
    public static final int MAX_UTF8_BYTES = 72;

    private PasswordPolicy() {
    }

    /** Returns why the password is not acceptable, or empty if it is. */
    public static Optional<String> violation(String password) {
        if (password == null || password.codePointCount(0, password.length()) < MIN_CHARACTERS) {
            return Optional.of("must be at least " + MIN_CHARACTERS + " characters");
        }
        if (exceedsBcryptLimit(password)) {
            return Optional.of("must be at most " + MAX_UTF8_BYTES + " bytes in UTF-8");
        }
        return Optional.empty();
    }

    public static boolean exceedsBcryptLimit(String password) {
        return password.getBytes(StandardCharsets.UTF_8).length > MAX_UTF8_BYTES;
    }
}
