package com.stampede.inventory.seed;

import java.util.List;

/**
 * The demo event to create for a seeded venue.
 *
 * <p>{@code dropInHours} and {@code startsInDays} are offsets from the moment of the <em>first</em>
 * load. Later loads leave the existing event untouched, so these times don't move; the dev-only
 * {@code demo-reschedule} profile ({@link DemoRescheduleRunner}) moves them forward again.
 */
public record EventSeedFile(
        Integer formatVersion,
        String slug,
        String venueSlug,
        String title,
        String status,
        String strategy,
        Integer perUserLimit,
        Integer dropInHours,
        Integer startsInDays,
        List<Tier> tiers) {

    public record Tier(String name, Long pricePaise) {}
}
