package com.stampede.inventory.strategy;

import com.stampede.inventory.api.RedisHoldState;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * {@link RedisHoldState} for invariant 5 (D-022, M2.2c-2 and M2.2d), with {@code redis/state.lua}.
 * <p>
 * <b>{@link #read}: many short commands, so buyers' commands run in between</b> (M2.2d). One script over
 * the whole event blocked Redis for ~40 ms on a loaded 2,000-seat event. Now: a header (clock, limit,
 * seat set, load epoch), SCAN pages from Java to find the event's keys, then short {@code PAGE} scripts
 * of {@value #SEAT_PAGE} seats, {@value #USER_PAGE} users or {@value #HOLD_PAGE} holds, then a trailer
 * (the unapplied stream entries, the epoch again). Each page reads a seat with the hold it names, and a
 * user with the holds their members name, so the pairs I10 interprets together are from one instant.
 * The whole read is not: invariant 5 re-reads whatever disagrees, atomically, after its Postgres snapshot
 * (see {@code RedisConsistencyCheck}). If the event was reloaded during the read (the epoch changed), the
 * read is repeated once, then it fails.
 * <p>
 * <b>{@link #reread}: one atomic script</b> over the listed keys, unchanged.
 * <p>
 * Key names come from {@link RedisKeys} with this application's prefix only, so it never reads another
 * application's (or another test context's) keys.
 */
@Component
class RedisHoldStateReader implements RedisHoldState {

    /** Page sizes, measured (D-022 M2.2d): each page script stays well under a millisecond. */
    static final int SEAT_PAGE = 100;
    static final int USER_PAGE = 50;
    static final int HOLD_PAGE = 50;
    /** Keys SCAN looks at per call (a hint to Redis, not an exact page size). */
    static final int SCAN_COUNT = 500;

    private final StringRedisTemplate redis;
    private final RedisKeys keys;
    private final RedisScript<List> stateScript;
    /** Test hook only: runs after each step of the paged read, with the step's name. */
    private volatile Consumer<String> afterStep = step -> { };

    RedisHoldStateReader(StringRedisTemplate redis, RedisInventoryProperties properties) {
        this.redis = redis;
        this.keys = new RedisKeys(properties.keyPrefix());
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("redis/state.lua"));
        script.setResultType(List.class);
        this.stateScript = script;
    }

    /** For tests: change Redis between the steps of the paged read ("header", "scan", "seats", ...). */
    void afterStep(Consumer<String> hook) {
        this.afterStep = hook;
    }

    @Override
    public Optional<Snapshot> read(UUID eventId, Duration entriesYoungerThan) {
        Paged first = readInPages(eventId, entriesYoungerThan);
        if (!(first instanceof Paged.Reloaded)) {
            return first.snapshot();
        }
        Paged second = readInPages(eventId, entriesYoungerThan);
        if (second instanceof Paged.Reloaded) {
            throw new IllegalStateException("event " + eventId + " was reloaded in Redis during two reads in a row");
        }
        return second.snapshot();
    }

    @Override
    public Optional<Snapshot> reread(UUID eventId, Duration entriesYoungerThan, Collection<UUID> seatIds,
                                     Collection<UUID> holdIds, Collection<UUID> userIds) {
        return run("KEYS", eventId, entriesYoungerThan, seatIds, holdIds, userIds).map(Reply::snapshot);
    }

    /** What one paged read found. */
    private sealed interface Paged {
        Optional<Snapshot> snapshot();

        record Done(Optional<Snapshot> snapshot) implements Paged {
        }

        /** The load epoch changed between the header and the trailer: the pages may mix two loads. */
        record Reloaded() implements Paged {
            @Override
            public Optional<Snapshot> snapshot() {
                return Optional.empty();
            }
        }
    }

    private Paged readInPages(UUID eventId, Duration window) {
        Optional<Reply> header = run("KEYS", eventId, window, List.of(), List.of(), List.of());
        afterStep.accept("header");
        if (header.isEmpty()) {
            return new Paged.Done(Optional.empty());
        }

        // Which seats, holds and users have keys. SCAN pages from here, not one SCAN inside a script.
        Set<UUID> seatIds = new LinkedHashSet<>();
        Set<UUID> holdIds = new LinkedHashSet<>();
        Set<UUID> userIds = new LinkedHashSet<>();
        String prefix = keys.eventPrefix(eventId);
        try (Cursor<String> cursor = redis.scan(ScanOptions.scanOptions().match(prefix + "*").count(SCAN_COUNT).build())) {
            cursor.forEachRemaining(key -> {
                String rest = key.substring(prefix.length());
                if (rest.startsWith("seat:")) {
                    seatIds.add(UUID.fromString(rest.substring(5)));
                } else if (rest.startsWith("hold:")) {
                    holdIds.add(UUID.fromString(rest.substring(5)));
                } else if (rest.startsWith("user:")) {
                    userIds.add(UUID.fromString(rest.substring(5)));
                }
            });
        }
        afterStep.accept("scan");

        Map<UUID, SeatValue> seatValues = new HashMap<>();
        Map<UUID, Hold> holds = new HashMap<>();
        Map<UUID, List<CapMember>> capMembers = new HashMap<>();
        for (List<UUID> page : pages(seatIds, SEAT_PAGE)) {
            readPage(eventId, window, page, List.of(), List.of(), seatValues, holds, capMembers);
            afterStep.accept("seats");
        }
        for (List<UUID> page : pages(userIds, USER_PAGE)) {
            readPage(eventId, window, List.of(), List.of(), page, seatValues, holds, capMembers);
            afterStep.accept("users");
        }
        // Holds no seat or member named: a hold whose seats were all sold or freed, say.
        Set<UUID> unread = new LinkedHashSet<>(holdIds);
        unread.removeAll(holds.keySet());
        for (List<UUID> page : pages(unread, HOLD_PAGE)) {
            readPage(eventId, window, List.of(), page, List.of(), seatValues, holds, capMembers);
            afterStep.accept("holds");
        }

        Optional<Reply> trailer = run("KEYS", eventId, window, List.of(), List.of(), List.of());
        afterStep.accept("trailer");
        if (trailer.isEmpty()) {
            return new Paged.Done(Optional.empty());   // unloaded during the read: like an event not loaded
        }
        if (!trailer.get().loadEpoch().equals(header.get().loadEpoch())) {
            return new Paged.Reloaded();
        }
        Snapshot start = header.get().snapshot();
        // Judged at the header's clock; unapplied entries from the trailer, so holds written during the
        // read have theirs.
        return new Paged.Done(Optional.of(new Snapshot(start.now(), start.perUserLimit(), start.seatIds(), seatValues,
                holds, capMembers, trailer.get().snapshot().unappliedEntries())));
    }

    /**
     * One page script. A value read later replaces an earlier one (a hold named by both a seat page and
     * a user page): every value is real, only not all from the same instant.
     */
    private void readPage(UUID eventId, Duration window, Collection<UUID> seatIds, Collection<UUID> holdIds,
                          Collection<UUID> userIds, Map<UUID, SeatValue> seatValues, Map<UUID, Hold> holds,
                          Map<UUID, List<CapMember>> capMembers) {
        Optional<Reply> page = run("PAGE", eventId, window, seatIds, holdIds, userIds);
        if (page.isEmpty()) {
            return;   // unloaded mid-read: the trailer sees it too and the read reports "not loaded"
        }
        seatValues.putAll(page.get().snapshot().seatValues());
        holds.putAll(page.get().snapshot().holds());
        capMembers.putAll(page.get().snapshot().capMembers());
    }

    private static List<List<UUID>> pages(Collection<UUID> ids, int size) {
        List<UUID> all = new ArrayList<>(ids);
        List<List<UUID>> pages = new ArrayList<>();
        for (int from = 0; from < all.size(); from += size) {
            pages.add(all.subList(from, Math.min(from + size, all.size())));
        }
        return pages;
    }

    /** One reply of state.lua and the load epoch it saw. */
    private record Reply(Snapshot snapshot, String loadEpoch) {
    }

    @SuppressWarnings("unchecked")
    private Optional<Reply> run(String mode, UUID eventId, Duration window, Collection<UUID> seatIds,
                                Collection<UUID> holdIds, Collection<UUID> userIds) {
        List<String> args = new ArrayList<>();
        args.add(mode);
        args.add(keys.eventPrefix(eventId));
        args.add(RedisKeys.GROUP);
        args.add(eventId.toString());
        args.add(Long.toString(window.toMillis()));
        for (Collection<UUID> ids : List.of(seatIds, holdIds, userIds)) {
            args.add(Integer.toString(ids.size()));
            ids.forEach(id -> args.add(id.toString()));
        }
        List<Object> reply = (List<Object>) redis.execute(stateScript,
                List.of(keys.meta(eventId), keys.seats(eventId), keys.stream()), args.toArray());
        if (reply == null || reply.isEmpty() || "NOT_LOADED".equals(reply.get(0))) {
            return Optional.empty();
        }
        return Optional.of(new Reply(parse(reply), (String) reply.get(8)));
    }

    @SuppressWarnings("unchecked")
    private static Snapshot parse(List<Object> reply) {
        Instant now = Instant.ofEpochMilli(Long.parseLong((String) reply.get(1)));
        int limit = Integer.parseInt((String) reply.get(2));

        Set<UUID> seatIds = new HashSet<>();
        for (Object id : (List<Object>) reply.get(3)) {
            seatIds.add(UUID.fromString((String) id));
        }

        Map<UUID, SeatValue> seatValues = new HashMap<>();
        List<Object> seats = (List<Object>) reply.get(4);
        for (int i = 0; i < seats.size(); i += 2) {
            seatValues.put(UUID.fromString((String) seats.get(i)), seatValue((String) seats.get(i + 1)));
        }

        Map<UUID, Hold> holds = new HashMap<>();
        List<Object> holdFields = (List<Object>) reply.get(5);
        for (int i = 0; i < holdFields.size(); i += 6) {
            UUID holdId = UUID.fromString((String) holdFields.get(i));
            List<UUID> holdSeats = Arrays.stream(((String) holdFields.get(i + 2)).split(","))
                    .map(UUID::fromString).toList();
            holds.put(holdId, new Hold(holdId, UUID.fromString((String) holdFields.get(i + 1)), holdSeats,
                    Instant.ofEpochMilli(Long.parseLong((String) holdFields.get(i + 3))),
                    Instant.ofEpochMilli(Long.parseLong((String) holdFields.get(i + 4))),
                    "-1".equals(holdFields.get(i + 5))));
        }

        Map<UUID, List<CapMember>> capMembers = new HashMap<>();
        List<Object> users = (List<Object>) reply.get(6);
        for (int i = 0; i < users.size(); i += 2) {
            List<Object> members = (List<Object>) users.get(i + 1);
            List<CapMember> parsed = new ArrayList<>();
            for (int m = 0; m < members.size(); m += 2) {
                String member = (String) members.get(m);
                String score = (String) members.get(m + 1);
                int colon = member.lastIndexOf(':');
                parsed.add(new CapMember(UUID.fromString(member.substring(0, colon)),
                        Integer.parseInt(member.substring(colon + 1)),
                        score.equals("inf") ? null : Instant.ofEpochMilli((long) Double.parseDouble(score))));
            }
            capMembers.put(UUID.fromString((String) users.get(i)), parsed);
        }

        List<UnappliedEntry> unapplied = new ArrayList<>();
        List<Object> entries = (List<Object>) reply.get(7);
        for (int i = 0; i < entries.size(); i += 3) {
            unapplied.add(new UnappliedEntry(UUID.fromString((String) entries.get(i)), (String) entries.get(i + 1),
                    Instant.ofEpochMilli(Long.parseLong((String) entries.get(i + 2)))));
        }
        return new Snapshot(now, limit, seatIds, seatValues, holds, capMembers, unapplied);
    }

    private static SeatValue seatValue(String raw) {
        String[] parts = raw.split(":");
        try {
            if (parts.length == 3 && parts[0].equals("H")) {
                return new SeatValue.Held(UUID.fromString(parts[1]));
            }
            if (parts.length == 3 && parts[0].equals("S")) {
                return new SeatValue.Sold(UUID.fromString(parts[1]));
            }
        } catch (IllegalArgumentException e) {
            // falls through: not a value any script writes
        }
        return new SeatValue.Unreadable(raw);
    }
}
