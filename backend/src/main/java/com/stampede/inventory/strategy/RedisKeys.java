package com.stampede.inventory.strategy;

import java.util.UUID;

/**
 * The names of every inventory key in Redis (D-022). The only place a key format is written down in
 * Java; hold.lua and release.lua get their keys and prefixes from here.
 *
 * <pre>
 * p:ev:{event}:meta          hash    limit (per-user limit), seatCount; exists = "event loaded"
 * p:ev:{event}:seats         set     every seat id of the event
 * p:ev:{event}:seat:{seat}   string  H:{holdId}:{createdMs} while held (expires with the hold)
 * p:ev:{event}:hold:{hold}   hash    user, seats, n, createdMs, expiresMs (expires with its seats)
 * p:ev:{event}:user:{user}   zset    member {holdId}:{seat count}, score = expiry in ms
 * p:inv:stream               stream  HELD / RELEASED entries for the Postgres consumer (M2.2b)
 * </pre>
 */
final class RedisKeys {

    private final String prefix;

    RedisKeys(String prefix) {
        this.prefix = prefix;
    }

    /** Every key of this application starts with this (the prefix and its separator). */
    String all() {
        return prefix + ":";
    }

    String meta(UUID eventId) {
        return event(eventId) + "meta";
    }

    String seats(UUID eventId) {
        return event(eventId) + "seats";
    }

    /** A seat's key is this followed by the seat id. */
    String seatPrefix(UUID eventId) {
        return event(eventId) + "seat:";
    }

    String seat(UUID eventId, UUID seatId) {
        return seatPrefix(eventId) + seatId;
    }

    String hold(UUID eventId, UUID holdId) {
        return event(eventId) + "hold:" + holdId;
    }

    /** A user's key is this followed by the user id. */
    String userPrefix(UUID eventId) {
        return event(eventId) + "user:";
    }

    String user(UUID eventId, UUID userId) {
        return userPrefix(eventId) + userId;
    }

    String stream() {
        return prefix + ":inv:stream";
    }

    private String event(UUID eventId) {
        return prefix + ":ev:" + eventId + ":";
    }
}
