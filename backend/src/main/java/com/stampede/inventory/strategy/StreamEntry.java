package com.stampede.inventory.strategy;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One entry of the inventory stream ({@code p:inv:stream}), as hold.lua and release.lua write it
 * (D-022). Both kinds carry the hold's facts, so either one can create the hold's Postgres row.
 *
 * @param id     the stream entry id ({@code <ms>-<seq>})
 * @param reason for RELEASED only: RELEASED or SAGA_FAILED (an EXPIRED release never writes an entry)
 */
record StreamEntry(String id, Type type, HoldFacts hold, ReleaseReason reason) {

    enum Type { HELD, RELEASED }

    /**
     * What Redis knows about a hold: enough to insert its {@code holds} row. Times are Redis' clock.
     */
    record HoldFacts(UUID eventId, UUID holdId, UUID userId, List<UUID> seatIds, Instant createdAt,
                     Instant expiresAt) {

        HoldFacts {
            seatIds = List.copyOf(seatIds);
            if (seatIds.isEmpty() || !expiresAt.isAfter(createdAt)) {
                throw new IllegalArgumentException("Hold " + holdId + " has no seats or does not expire after it "
                        + "was created");
            }
        }

        static HoldFacts of(UUID eventId, RedisHoldStore.LiveHold live) {
            return new HoldFacts(eventId, live.holdId(), live.userId(), live.seatIds(), live.createdAt(),
                    live.expiresAt());
        }
    }

    /**
     * Parses an entry. A malformed entry throws {@link IllegalArgumentException}, which the consumer
     * treats as a non-transient failure: it can never succeed, so it ends in the dead-letter stream.
     */
    static StreamEntry parse(String id, Map<?, ?> fields) {
        try {
            Type type = Type.valueOf(field(fields, "type"));
            HoldFacts facts = new HoldFacts(
                    UUID.fromString(field(fields, "event")),
                    UUID.fromString(field(fields, "hold")),
                    UUID.fromString(field(fields, "user")),
                    Arrays.stream(field(fields, "seats").split(",")).map(UUID::fromString).toList(),
                    Instant.ofEpochMilli(Long.parseLong(field(fields, "createdMs"))),
                    Instant.ofEpochMilli(Long.parseLong(field(fields, "expiresMs"))));
            ReleaseReason reason = type == Type.RELEASED ? ReleaseReason.valueOf(field(fields, "reason")) : null;
            if (reason == ReleaseReason.EXPIRED) {
                throw new IllegalArgumentException("release.lua never writes an EXPIRED entry");
            }
            return new StreamEntry(id, type, facts, reason);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IllegalArgumentException("Malformed stream entry " + id + ": " + fields, e);
        }
    }

    private static String field(Map<?, ?> fields, String name) {
        Object value = fields.get(name);
        if (value == null) {
            throw new IllegalArgumentException("missing field '" + name + "'");
        }
        return value.toString();
    }
}
