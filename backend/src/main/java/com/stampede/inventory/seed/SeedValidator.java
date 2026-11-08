package com.stampede.inventory.seed;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Checks a venue file and its event file before anything is written. Collects every problem so one
 * run reports them all.
 *
 * <p>Status, strategy and per-user-limit values are also checked by the database CHECK constraints;
 * here we check what the database can't, or can't explain well.
 */
final class SeedValidator {

    static final int SUPPORTED_FORMAT_VERSION = 1;

    /** Slugs, codes, row labels and tier names become parts of seed ids, joined with ':' (see SeedIds). */
    private static final Pattern NAME_PART = Pattern.compile("[A-Za-z0-9_-]+");

    private final List<String> errors = new ArrayList<>();

    private SeedValidator() {}

    static void validate(VenueFile venue, EventSeedFile event) {
        SeedValidator v = new SeedValidator();
        v.checkEvent(event);
        v.checkVenue(venue, tierNames(event));
        if (venue != null && event != null && venue.slug() != null
                && !venue.slug().equals(event.venueSlug())) {
            v.errors.add("event " + event.slug() + " is for venue '" + event.venueSlug()
                    + "' but the venue file is '" + venue.slug() + "'");
        }
        if (!v.errors.isEmpty()) {
            throw new SeedException("Invalid seed files (nothing was written):\n  - "
                    + String.join("\n  - ", v.errors));
        }
    }

    private static Set<String> tierNames(EventSeedFile event) {
        if (event == null || event.tiers() == null) {
            return Set.of();
        }
        return event.tiers().stream()
                .map(EventSeedFile.Tier::name)
                .filter(n -> n != null)
                .collect(Collectors.toSet());
    }

    private void checkEvent(EventSeedFile event) {
        if (event == null) {
            errors.add("event file is empty");
            return;
        }
        if (!Integer.valueOf(SUPPORTED_FORMAT_VERSION).equals(event.formatVersion())) {
            errors.add("event: formatVersion must be " + SUPPORTED_FORMAT_VERSION);
        }
        namePart("event slug", event.slug());
        required("event title", event.title());
        required("event status", event.status());
        required("event strategy", event.strategy());
        if (event.perUserLimit() == null) {
            errors.add("event: perUserLimit is required");
        }
        if (event.dropInHours() == null || event.dropInHours() < 0) {
            errors.add("event: dropInHours must be >= 0");
        }
        if (event.startsInDays() == null || event.startsInDays() < 0) {
            errors.add("event: startsInDays must be >= 0");
        }
        if (event.tiers() == null || event.tiers().isEmpty()) {
            errors.add("event: at least one tier is required");
            return;
        }
        Set<String> seen = new HashSet<>();
        for (EventSeedFile.Tier tier : event.tiers()) {
            namePart("tier name", tier.name());
            if (tier.name() != null && !seen.add(tier.name())) {
                errors.add("duplicate tier: " + tier.name());
            }
            if (tier.pricePaise() == null || tier.pricePaise() < 0) {
                errors.add("tier " + tier.name() + ": pricePaise must be >= 0");
            }
        }
    }

    private void checkVenue(VenueFile venue, Set<String> tierNames) {
        if (venue == null) {
            errors.add("venue file is empty");
            return;
        }
        if (!Integer.valueOf(SUPPORTED_FORMAT_VERSION).equals(venue.formatVersion())) {
            errors.add("venue: formatVersion must be " + SUPPORTED_FORMAT_VERSION);
        }
        namePart("venue slug", venue.slug());
        required("venue name", venue.name());
        if (venue.width() == null || venue.width() <= 0 || venue.height() == null || venue.height() <= 0) {
            errors.add("venue: width and height must be > 0");
            return;  // seat bounds checks below need them
        }
        if (venue.seatRadius() == null || venue.seatRadius() <= 0) {
            errors.add("venue: seatRadius must be > 0");
        }
        if (venue.stage() == null) {
            errors.add("venue: stage is required");
        }
        if (venue.sections() == null || venue.sections().isEmpty()) {
            errors.add("venue: at least one section is required");
            return;
        }
        Set<String> sectionCodes = new HashSet<>();
        for (VenueFile.Section section : venue.sections()) {
            checkSection(venue, section, sectionCodes, tierNames);
        }
    }

    private void checkSection(VenueFile venue, VenueFile.Section section, Set<String> sectionCodes,
                              Set<String> tierNames) {
        String code = section.code();
        namePart("section code", code);
        if (code != null && !sectionCodes.add(code)) {
            errors.add("duplicate section code: " + code);
        }
        required("section " + code + " name", section.name());
        if (section.defaultTier() == null || !tierNames.contains(section.defaultTier())) {
            errors.add("section " + code + ": defaultTier '" + section.defaultTier()
                    + "' is not one of the event's tiers " + tierNames.stream().sorted().toList());
        }
        if (section.label() == null) {
            errors.add("section " + code + ": label is required");
        }
        if (section.outline() == null || section.outline().size() < 3
                || section.outline().stream().anyMatch(p -> p == null || p.size() != 2)) {
            errors.add("section " + code + ": outline must be a polygon of at least 3 [x, y] points");
        }
        if (section.rows() == null || section.rows().isEmpty()) {
            errors.add("section " + code + ": at least one row is required");
            return;
        }
        Set<String> rowLabels = new HashSet<>();
        for (VenueFile.Row row : section.rows()) {
            namePart("section " + code + " row label", row.label());
            if (row.label() != null && !rowLabels.add(row.label())) {
                errors.add("duplicate row: section " + code + ", row " + row.label());
            }
            checkSeats(venue, code, row);
        }
    }

    private void checkSeats(VenueFile venue, String code, VenueFile.Row row) {
        if (row.seats() == null || row.seats().isEmpty()) {
            errors.add("section " + code + ", row " + row.label() + ": at least one seat is required");
            return;
        }
        Set<Integer> numbers = new HashSet<>();
        for (VenueFile.Seat seat : row.seats()) {
            String where = "section " + code + ", row " + row.label() + ", seat " + seat.number();
            if (seat.number() == null || seat.number() <= 0) {
                errors.add(where + ": number must be > 0");
            } else if (!numbers.add(seat.number())) {
                errors.add("duplicate seat: " + where);
            }
            if (!inRange(seat.x(), venue.width()) || !inRange(seat.y(), venue.height())) {
                errors.add(where + ": x/y must be inside the venue (0..width, 0..height)");
            }
        }
    }

    private static boolean inRange(Double value, int max) {
        return value != null && Double.isFinite(value) && value >= 0 && value <= max;
    }

    private void required(String what, String value) {
        if (value == null || value.isBlank()) {
            errors.add(what + " is required");
        }
    }

    private void namePart(String what, String value) {
        if (value == null || !NAME_PART.matcher(value).matches()) {
            errors.add(what + " '" + value + "' must match " + NAME_PART.pattern());
        }
    }
}
