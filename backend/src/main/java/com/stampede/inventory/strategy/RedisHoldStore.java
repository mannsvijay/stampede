package com.stampede.inventory.strategy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * Seat holds in Redis: the fast path of the Redis-first strategy (PRD 6.2 C, D-022, M2.2a).
 * <p>
 * Each change is one Lua script ({@code redis/hold.lua}, {@code redis/release.lua}). Redis runs a
 * script as a single command, so the "is every seat free, is the user under the cap" check and the
 * writes that follow can't be interleaved with another buyer's. Expiry needs no code at all: every
 * key of a hold carries the same expiry time and Redis deletes them.
 * <p>
 * This class only talks to Redis. It is not an {@link InventoryStrategy} yet: holds reach Postgres
 * through the stream consumer ({@link RedisStreamConsumer}, M2.2b), and the strategy bean (with sell)
 * is M2.2c. Until then no event can use it.
 */
@Component
class RedisHoldStore {

    private final StringRedisTemplate redis;
    private final RedisKeys keys;
    private final RedisScript<List> holdScript = script("redis/hold.lua");
    private final RedisScript<List> releaseScript = script("redis/release.lua");
    private final RedisScript<List> loadScript = script("redis/load.lua");

    RedisHoldStore(StringRedisTemplate redis, RedisInventoryProperties properties) {
        this.redis = redis;
        this.keys = new RedisKeys(properties.keyPrefix());
    }

    /** The result of {@link #release}. */
    sealed interface Release {

        /** The hold was live; its seats are free again and no longer count towards the user's cap. */
        record Released(List<UUID> seatIds) implements Release {
        }

        /** An EXPIRED release of a hold that Redis still has: nothing changed. */
        record NotExpired(Instant expiresAt) implements Release {
        }

        /** Redis has no such hold: it expired, was released already, or never existed. */
        record Gone() implements Release {
        }
    }

    /** A hold Redis currently has, for tests and (M2.2c) the Redis invariant. */
    record LiveHold(UUID holdId, UUID userId, List<UUID> seatIds, Instant createdAt, Instant expiresAt) {
    }

    /**
     * Tells Redis which seats an event has and its per-user limit. Until this has run for an event,
     * {@link #hold} refuses it. Safe to repeat. (M2.2b adds the loader that reads these from Postgres.)
     */
    void loadEvent(UUID eventId, Collection<UUID> seatIds, int perUserLimit) {
        if (seatIds.isEmpty() || perUserLimit < 1) {
            throw new IllegalArgumentException("An event needs at least one seat and a positive per-user limit");
        }
        redis.opsForSet().add(keys.seats(eventId), seatIds.stream().map(UUID::toString).toArray(String[]::new));
        // The meta hash is written last: hold.lua treats its existence as "the seat set is complete".
        redis.opsForHash().putAll(keys.meta(eventId), Map.of(
                "limit", Integer.toString(perUserLimit),
                "seatCount", Integer.toString(seatIds.size())));
    }

    /**
     * Holds every requested seat, or none. Rejections come in the usual precedence (UNKNOWN_SEAT,
     * SEAT_TAKEN, LIMIT_EXCEEDED); CONTENDED never happens here, because nothing is retried.
     *
     * @throws IllegalStateException if the event was not loaded, or the hold id is already live
     */
    HoldOutcome hold(HoldRequest request) {
        UUID eventId = request.eventId();
        List<String> scriptKeys = List.of(
                keys.meta(eventId),
                keys.seats(eventId),
                keys.user(eventId, request.userId()),
                keys.hold(eventId, request.holdId()),
                keys.stream());
        List<String> args = new ArrayList<>();
        args.add(keys.seatPrefix(eventId));
        args.add(request.holdId().toString());
        args.add(request.userId().toString());
        args.add(eventId.toString());
        args.add(Long.toString(request.holdFor().toMillis()));
        request.seatIds().forEach(seatId -> args.add(seatId.toString()));

        List<String> reply = run(holdScript, scriptKeys, args);
        return switch (reply.get(0)) {
            case "HELD" -> new HoldOutcome.Held(request.holdId(), request.seatIds(),
                    Instant.ofEpochMilli(Long.parseLong(reply.get(1))));
            case "UNKNOWN_SEAT" -> new HoldOutcome.Rejected(HoldRejection.UNKNOWN_SEAT, uuids(reply, 1));
            case "SEAT_TAKEN" -> new HoldOutcome.Rejected(HoldRejection.SEAT_TAKEN, uuids(reply, 2),
                    Long.parseLong(reply.get(1)));
            case "LIMIT_EXCEEDED" -> new HoldOutcome.Rejected(HoldRejection.LIMIT_EXCEEDED, request.seatIds());
            case "NOT_LOADED" -> throw new IllegalStateException("Event " + eventId + " is not loaded into Redis");
            case "DUPLICATE_HOLD" -> throw new IllegalStateException("Hold id " + request.holdId()
                    + " is already live in Redis; every attempt needs a new id (D-015)");
            default -> throw new IllegalStateException("Unexpected reply from hold.lua: " + reply);
        };
    }

    /**
     * Releases a live hold. With {@link ReleaseReason#EXPIRED} nothing is ever deleted: a hold Redis
     * still has is not expired by Redis' clock, and one that did expire is already gone.
     */
    Release release(UUID eventId, UUID holdId, ReleaseReason reason) {
        List<String> scriptKeys = List.of(keys.hold(eventId, holdId), keys.stream());
        List<String> args = List.of(keys.seatPrefix(eventId), keys.userPrefix(eventId), holdId.toString(),
                eventId.toString(), reason.name());

        List<String> reply = run(releaseScript, scriptKeys, args);
        return switch (reply.get(0)) {
            case "RELEASED" -> new Release.Released(uuids(reply, 1));
            case "NOT_EXPIRED" -> new Release.NotExpired(Instant.ofEpochMilli(Long.parseLong(reply.get(1))));
            case "GONE" -> new Release.Gone();
            default -> throw new IllegalStateException("Unexpected reply from release.lua: " + reply);
        };
    }

    /** The hold that has this seat right now, if any. */
    Optional<UUID> holderOf(UUID eventId, UUID seatId) {
        String owner = redis.opsForValue().get(keys.seat(eventId, seatId));
        if (owner == null || !owner.startsWith("H:")) {
            return Optional.empty();
        }
        return Optional.of(UUID.fromString(owner.split(":")[1]));
    }

    /** The hold as Redis has it, or empty once it expired or was released. */
    Optional<LiveHold> liveHold(UUID eventId, UUID holdId) {
        Map<Object, Object> hash = redis.opsForHash().entries(keys.hold(eventId, holdId));
        if (hash.isEmpty()) {
            return Optional.empty();
        }
        List<UUID> seatIds = Arrays.stream(((String) hash.get("seats")).split(",")).map(UUID::fromString).toList();
        return Optional.of(new LiveHold(holdId, UUID.fromString((String) hash.get("user")), seatIds,
                Instant.ofEpochMilli(Long.parseLong((String) hash.get("createdMs"))),
                Instant.ofEpochMilli(Long.parseLong((String) hash.get("expiresMs")))));
    }

    /** How many seats count towards the user's cap right now: the seats of their unexpired holds. */
    int used(UUID eventId, UUID userId) {
        Long now = redis.execute((RedisCallback<Long>) connection -> connection.serverCommands().time());
        Set<String> live = redis.opsForZSet().rangeByScore(keys.user(eventId, userId), now, Double.POSITIVE_INFINITY);
        int used = 0;
        for (String member : live) {
            used += Integer.parseInt(member.substring(member.lastIndexOf(':') + 1));
        }
        return used;
    }

    /** A snapshot of one event from Postgres, as {@link RedisEventLoader} reads it. */
    record EventSnapshot(int perUserLimit, List<UUID> seatIds, List<SoldSeat> soldSeats,
                         List<SoldMember> soldMembers, List<LiveHold> activeHolds) {
    }

    /** A SOLD seat: the marker {@code S:<orderId>:<soldMs>}. */
    record SoldSeat(UUID seatId, UUID orderId, Instant soldAt) {
    }

    /** The bought seats of one order, counted towards its buyer's cap forever (member score +inf). */
    record SoldMember(UUID userId, UUID holdId, int seatCount) {
    }

    /** The result of {@link #load}. */
    sealed interface Load {

        /** The event was not loaded; now it is. Holds that had already expired were skipped. */
        record Loaded(int restoredHolds, int skippedHolds) implements Load {
        }

        /** The event was loaded already (meta exists); nothing was written. */
        record AlreadyLoaded() implements Load {
        }
    }

    /**
     * Loads an event from a Postgres snapshot with {@code redis/load.lua}: in one atomic step, and only
     * if the event is not loaded yet. Never touches a loaded event (see the script).
     */
    Load load(UUID eventId, EventSnapshot snapshot) {
        if (snapshot.seatIds().isEmpty() || snapshot.perUserLimit() < 1) {
            throw new IllegalArgumentException("An event needs at least one seat and a positive per-user limit");
        }
        List<String> args = new ArrayList<>();
        args.add(keys.seatPrefix(eventId));
        args.add(keys.userPrefix(eventId));
        args.add(keys.holdPrefix(eventId));
        args.add(Integer.toString(snapshot.perUserLimit()));
        args.add(Integer.toString(snapshot.seatIds().size()));
        snapshot.seatIds().forEach(seatId -> args.add(seatId.toString()));
        args.add(Integer.toString(snapshot.soldSeats().size()));
        for (SoldSeat sold : snapshot.soldSeats()) {
            args.add(sold.seatId().toString());
            args.add(sold.orderId().toString());
            args.add(Long.toString(sold.soldAt().toEpochMilli()));
        }
        args.add(Integer.toString(snapshot.soldMembers().size()));
        for (SoldMember member : snapshot.soldMembers()) {
            args.add(member.userId().toString());
            args.add(member.holdId() + ":" + member.seatCount());
        }
        args.add(Integer.toString(snapshot.activeHolds().size()));
        for (LiveHold hold : snapshot.activeHolds()) {
            args.add(hold.holdId().toString());
            args.add(hold.userId().toString());
            args.add(Long.toString(hold.createdAt().toEpochMilli()));
            args.add(Long.toString(hold.expiresAt().toEpochMilli()));
            args.add(String.join(",", hold.seatIds().stream().map(UUID::toString).toList()));
            args.add(Integer.toString(hold.seatIds().size()));
        }

        List<String> reply = run(loadScript, List.of(keys.meta(eventId), keys.seats(eventId)), args);
        return switch (reply.get(0)) {
            case "LOADED" -> new Load.Loaded(Integer.parseInt(reply.get(1)), Integer.parseInt(reply.get(2)));
            case "ALREADY_LOADED" -> new Load.AlreadyLoaded();
            default -> throw new IllegalStateException("Unexpected reply from load.lua: " + reply);
        };
    }

    /** Whether the event is loaded: its meta hash exists (written last by every load). */
    boolean isLoaded(UUID eventId) {
        return Boolean.TRUE.equals(redis.hasKey(keys.meta(eventId)));
    }

    /**
     * The load epoch ({@code meta.loadedMs}), empty if the event is not loaded. A loaded event whose
     * meta has no epoch (loaded by {@link #loadEvent}) reads as {@code ""}. The reconciler compares
     * two readings to notice that Redis was wiped and reloaded in between.
     */
    Optional<String> loadEpoch(UUID eventId) {
        if (!isLoaded(eventId)) {
            return Optional.empty();
        }
        Object epoch = redis.opsForHash().get(keys.meta(eventId), "loadedMs");
        return Optional.of(epoch == null ? "" : (String) epoch);
    }

    /** Redis' clock in epoch milliseconds: the clock that decides REDIS_FIRST expiry (D-015). */
    long nowMillis() {
        Long now = redis.execute((RedisCallback<Long>) connection -> connection.serverCommands().time());
        if (now == null) {
            throw new IllegalStateException("Redis TIME returned nothing");
        }
        return now;
    }

    /** The ids of every hold Redis has for this event (SCAN, so Redis isn't blocked the way KEYS would). */
    List<UUID> liveHoldIds(UUID eventId) {
        String prefix = keys.holdPrefix(eventId);
        List<UUID> ids = new ArrayList<>();
        try (Cursor<String> cursor = redis.scan(ScanOptions.scanOptions().match(prefix + "*").count(1000).build())) {
            cursor.forEachRemaining(key -> ids.add(UUID.fromString(key.substring(prefix.length()))));
        }
        return ids;
    }

    /** The raw value of a seat key ({@code H:...} or {@code S:...}), or null if the seat is free. */
    String seatValue(UUID eventId, UUID seatId) {
        return redis.opsForValue().get(keys.seat(eventId, seatId));
    }

    /** Marks a seat sold ({@code S:<orderId>:<soldMs>}, no expiry), whatever the key held before. */
    void markSold(UUID eventId, SoldSeat sold) {
        redis.opsForValue().set(keys.seat(eventId, sold.seatId()),
                "S:" + sold.orderId() + ":" + sold.soldAt().toEpochMilli());
    }

    /** Whether the user's cap already counts this order's bought seats (a member with score +inf). */
    boolean hasSoldMember(UUID eventId, SoldMember member) {
        Double score = redis.opsForZSet().score(keys.user(eventId, member.userId()),
                member.holdId() + ":" + member.seatCount());
        return score != null && score.isInfinite();
    }

    /** Counts an order's bought seats towards its buyer's cap forever; the user key then never expires. */
    void addSoldMember(UUID eventId, SoldMember member) {
        String key = keys.user(eventId, member.userId());
        redis.opsForZSet().add(key, member.holdId() + ":" + member.seatCount(), Double.POSITIVE_INFINITY);
        redis.persist(key);
    }

    RedisKeys keys() {
        return keys;
    }

    @SuppressWarnings("unchecked")
    private List<String> run(RedisScript<List> script, List<String> scriptKeys, List<String> args) {
        // StringRedisTemplate sends keys and arguments as strings and reads the reply's items as strings.
        return (List<String>) redis.execute(script, scriptKeys, args.toArray());
    }

    private static RedisScript<List> script(String path) {
        // DefaultRedisScript sends the script's SHA-1 (EVALSHA) and falls back to the full text (EVAL)
        // when this Redis hasn't seen it yet, so the script isn't re-sent on every call.
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(path));
        script.setResultType(List.class);
        return script;
    }

    private static List<UUID> uuids(List<String> reply, int from) {
        return reply.subList(from, reply.size()).stream().map(UUID::fromString).toList();
    }
}
