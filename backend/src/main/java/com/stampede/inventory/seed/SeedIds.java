package com.stampede.inventory.seed;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Deterministic ids for seed rows (decision D-006).
 *
 * <p>{@link UUID#nameUUIDFromBytes} makes a version 3 UUID from an MD5 hash of a name, so the same
 * name gives the same id on every machine and every run. That makes the loader idempotent
 * ({@code INSERT ... ON CONFLICT (id) DO NOTHING}) and lets load tests target known seats.
 *
 * <p>Name parts are joined with ':'. {@link SeedValidator} only allows [A-Za-z0-9_-] in slugs, codes,
 * row labels and tier names, so two different seats can never produce the same name.
 */
public final class SeedIds {

    private static final String PREFIX = "stampede-seed";

    private SeedIds() {}

    public static UUID venue(String venueSlug) {
        return of("venue", venueSlug);
    }

    public static UUID section(String venueSlug, String sectionCode) {
        return of("section", venueSlug, sectionCode);
    }

    public static UUID seat(String venueSlug, String sectionCode, String rowLabel, int number) {
        return of("seat", venueSlug, sectionCode, rowLabel, Integer.toString(number));
    }

    public static UUID event(String eventSlug) {
        return of("event", eventSlug);
    }

    public static UUID tier(String eventSlug, String tierName) {
        return of("tier", eventSlug, tierName);
    }

    private static UUID of(String... parts) {
        String name = PREFIX + ":" + String.join(":", parts);
        return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
    }
}
