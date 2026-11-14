package com.stampede.inventory.strategy;

import com.stampede.inventory.api.RedisHoldState;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * {@link RedisHoldState} for invariant 5 (D-022, M2.2c-2): one read-only script ({@code redis/state.lua})
 * per call. Key names come from {@link RedisKeys} with this application's prefix only, so it never
 * reads another application's (or another test context's) keys.
 */
@Component
class RedisHoldStateReader implements RedisHoldState {

    private final StringRedisTemplate redis;
    private final RedisKeys keys;
    private final RedisScript<List> stateScript;

    RedisHoldStateReader(StringRedisTemplate redis, RedisInventoryProperties properties) {
        this.redis = redis;
        this.keys = new RedisKeys(properties.keyPrefix());
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("redis/state.lua"));
        script.setResultType(List.class);
        this.stateScript = script;
    }

    @Override
    public Optional<Snapshot> read(UUID eventId, Duration entriesYoungerThan) {
        return run(eventId, entriesYoungerThan, List.of("ALL"));
    }

    @Override
    public Optional<Snapshot> reread(UUID eventId, Duration entriesYoungerThan, Collection<UUID> seatIds,
                                     Collection<UUID> holdIds, Collection<UUID> userIds) {
        List<String> args = new ArrayList<>();
        args.add("KEYS");
        for (Collection<UUID> ids : List.of(seatIds, holdIds, userIds)) {
            args.add(Integer.toString(ids.size()));
            ids.forEach(id -> args.add(id.toString()));
        }
        return run(eventId, entriesYoungerThan, args);
    }

    @SuppressWarnings("unchecked")
    private Optional<Snapshot> run(UUID eventId, Duration window, List<String> modeArgs) {
        List<String> args = new ArrayList<>();
        args.add(modeArgs.get(0));
        args.add(keys.eventPrefix(eventId));
        args.add(RedisKeys.GROUP);
        args.add(eventId.toString());
        args.add(Long.toString(window.toMillis()));
        args.addAll(modeArgs.subList(1, modeArgs.size()));
        List<Object> reply = (List<Object>) redis.execute(stateScript,
                List.of(keys.meta(eventId), keys.seats(eventId), keys.stream()), args.toArray());
        if (reply == null || reply.isEmpty() || "NOT_LOADED".equals(reply.get(0))) {
            return Optional.empty();
        }
        return Optional.of(parse(reply));
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
