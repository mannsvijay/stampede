package com.stampede.identity.user;

import java.util.Locale;

/** Email normalisation. Users are looked up by lower(email), which the V1 unique index covers. */
public final class Emails {

    private Emails() {
    }

    public static String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
