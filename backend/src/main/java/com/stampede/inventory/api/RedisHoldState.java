package com.stampede.inventory.api;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Read-only view of one event's Redis-first hold state (D-022, M2.2c-2), for invariant 5 of PRD 6.10
 * ("Redis hold state is consistent with Postgres"), which lives in {@code control}.
 * <p>
 * It only reads, and only keys under this application's own key prefix
 * ({@code stampede.inventory.redis.key-prefix}), so two Spring test contexts sharing one Redis never
 * see each other's holds.
 * <p>
 * <b>Which reads are atomic</b> (D-022 M2.2d). {@link #reread} is ONE Lua script, so everything in its
 * {@link Snapshot} is from the same instant (Redis runs a script as a single command). {@link #read} is
 * not: one script over a whole event blocked every other Redis client for ~40 ms on a 2,000-seat event,
 * so it is taken in many short commands with buyers' commands in between. Each of its values is real, but
 * values may be from different instants (a seat and the hold it names, or a user and the holds their
 * members name, are always read together). Its {@code now} is the clock at its start. Invariant 5 copes by
 * reading whatever disagrees again, atomically, after its Postgres snapshot.
 * <p>
 * Every time is Redis' clock, the clock that decides expiry for REDIS_FIRST events.
 */
public interface RedisHoldState {

    /**
     * The whole event: limit, seat set, every seat key, every hold hash, every user's cap members, and
     * the stream entries for this event that are not yet applied to Postgres and younger than
     * {@code entriesYoungerThan}. Empty if the event is not loaded in Redis. NOT atomic (see above): many
     * short commands, so it never blocks Redis for long. Throws if the event was reloaded during two
     * reads in a row.
     */
    Optional<Snapshot> read(UUID eventId, Duration entriesYoungerThan);

    /**
     * The same, but only for the listed seats, holds and users (plus the holds the seats' values and
     * the users' members name, so a re-read value can be interpreted). Limit, seat set and unapplied
     * entries are always included. Empty if the event is not loaded (any more). Atomic: one script.
     */
    Optional<Snapshot> reread(UUID eventId, Duration entriesYoungerThan, Collection<UUID> seatIds,
                              Collection<UUID> holdIds, Collection<UUID> userIds);

    /**
     * @param now              Redis' clock when the script ran
     * @param seatValues       every seat key that exists (read scope), by seat id; a missing seat is free
     * @param holds            every hold hash that exists (read scope), expired-but-pinned ones included
     * @param capMembers       every member of each user's cap sorted set (read scope), live or not
     * @param unappliedEntries this event's stream entries not yet acknowledged by the consumer group
     */
    record Snapshot(Instant now, int perUserLimit, Set<UUID> seatIds, Map<UUID, SeatValue> seatValues,
                    Map<UUID, Hold> holds, Map<UUID, List<CapMember>> capMembers,
                    List<UnappliedEntry> unappliedEntries) {

        public Snapshot {
            seatIds = Set.copyOf(seatIds);
            seatValues = Map.copyOf(seatValues);
            holds = Map.copyOf(holds);
            capMembers = Map.copyOf(capMembers);
            unappliedEntries = List.copyOf(unappliedEntries);
        }
    }

    /** What a seat key says. */
    sealed interface SeatValue {

        /** {@code H:<holdId>:<createdMs>}: taken by this hold (it may be expired-but-pinned). */
        record Held(UUID holdId) implements SeatValue {
        }

        /** {@code S:<orderId>:<soldMs>}: sold to this order. */
        record Sold(UUID orderId) implements SeatValue {
        }

        /** Anything else: no script writes it. */
        record Unreadable(String raw) implements SeatValue {
        }
    }

    /**
     * A hold hash. {@code pinned} = no TTL: a sale pinned it ({@code pin.lua}) and has not finished
     * ({@code sold.lua}) or been undone yet.
     */
    record Hold(UUID holdId, UUID userId, List<UUID> seatIds, Instant createdAt, Instant expiresAt,
                boolean pinned) {

        public Hold {
            seatIds = List.copyOf(seatIds);
        }
    }

    /**
     * A member {@code <holdId>:<seat count>} of a user's cap set. {@code countsUntil} is its score;
     * null = {@code +inf}: counted forever (bought seats, or a hold pinned by a sale).
     */
    record CapMember(UUID holdId, int seatCount, Instant countsUntil) {
    }

    /** A HELD or RELEASED stream entry the consumer has not applied (acknowledged) yet. */
    record UnappliedEntry(UUID holdId, String type, Instant writtenAt) {
    }
}
