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
 * p:inv:dead                 stream  entries the consumer gave up on (dead letters, M2.2b)
 * </pre>
 * The consumer group on the stream is {@link #GROUP}.
 */
final class RedisKeys {

    /** The consumer group every instance's stream consumer joins (D-022, decision 1a). */
    static final String GROUP = "inventory-persister";

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
        return holdPrefix(eventId) + holdId;
    }

    /** A hold's key is this followed by the hold id (the reconciler scans for {@code holdPrefix*}). */
    String holdPrefix(UUID eventId) {
        return event(eventId) + "hold:";
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

    String deadLetters() {
        return prefix + ":inv:dead";
    }

    private String event(UUID eventId) {
        return prefix + ":ev:" + eventId + ":";
    }
}
