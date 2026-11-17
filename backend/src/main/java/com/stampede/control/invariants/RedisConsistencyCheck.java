package com.stampede.control.invariants;

import com.stampede.inventory.api.RedisHoldState;
import com.stampede.inventory.api.RedisHoldState.CapMember;
import com.stampede.inventory.api.RedisHoldState.SeatValue;
import com.stampede.inventory.api.RedisHoldState.Snapshot;
import com.stampede.inventory.api.RedisHoldState.UnappliedEntry;
import java.sql.Array;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Invariant 5 of PRD 6.10, rule {@code I10}: "Redis hold state is consistent with Postgres" (D-022,
 * M2.2c-2). Runs only for REDIS_FIRST events that are loaded in Redis.
 * <p>
 * <b>Three phases, in this order; Redis always before Postgres.</b>
 * <ol>
 *   <li>{@link #readFirst} reads the whole event from Redis BEFORE the checker opens its Postgres
 *       snapshot. Since M2.2d the read is taken in pages (many short commands, so buyers aren't blocked),
 *       so it is not from one instant.</li>
 *   <li>{@link #compareInSnapshot} reads Postgres inside the snapshot and compares. Every key that
 *       disagrees is kept for phase 3; nothing is reported yet.</li>
 *   <li>{@link #readAgainAndCompare}, after the snapshot: only those keys are read again, Redis (one
 *       atomic script) and then Postgres (a short read-only transaction), and compared again. Only what
 *       disagrees in both comparisons can be reported.</li>
 * </ol>
 * Why Redis first: for holds, Redis is ahead of Postgres (a hold is taken in Redis first), so reading Redis
 * first and Postgres second shows Postgres "behind", never "ahead". Postgres is ahead for a sale between
 * its commit and sold.lua, for a Postgres-wins tombstone before release.lua and for R3's markers; the
 * second read and the sale-finishing excuse cover those. Why read again: a change that landed during or
 * after the first read (a release, a newly applied hold, a torn paged read) is then seen on both sides.
 * Why Postgres again too (M2.2d): compared with the older snapshot, a change that reached Postgres and then
 * Redis before the re-read looked like drift (a lagging hold sold in between; a HELD entry applied and
 * acknowledged in between, leaving nothing to excuse it). Every input of a reported difference comes from
 * phase 3's reads, so a torn first read can cause extra reading but never a report of its own.
 * <p>
 * <b>Compared by time, not by status.</b> Both sides are judged at Redis' clock: a hold is live while
 * its expiry is in the future, whatever Postgres' status column says. So a hold whose Redis TTL ran out
 * and that the sweeper hasn't closed yet (up to one 5 s tick) is not a disagreement; a hold stuck ACTIVE
 * far past expiry is I9's job.
 * <p>
 * <b>What is compared:</b> the per-user limit; the seat set; every live hold (user, seats, created, expiry);
 * every seat (free, held by a live hold, sold to an order); and every user's cap (the live members of
 * their Redis set against their live ACTIVE plus CONVERTED holds in Postgres).
 * <p>
 * <b>Tolerance.</b> Strict ({@code grace} zero, every test, after the stream is drained): nothing is
 * excused. At runtime ({@code grace} positive), a difference is excused only if every hold it involves
 * <ul>
 *   <li>has a stream entry not yet applied by the consumer and younger than {@link #LAG_WINDOW} (2 s):
 *       consumer lag; or</li>
 *   <li>is pinned in Redis while Postgres already shows it CONVERTED, and its order is younger than the
 *       grace period: a sale between its Postgres commit and {@code sold.lua} (D-022 M2.2c-1, step 3),
 *       excused like O1 and O5 excuse a checkout between its steps.</li>
 * </ul>
 * Without a reader ({@code RedisHoldState} bean) a REDIS_FIRST event is reported as "cannot check",
 * never passed.
 */
final class RedisConsistencyCheck {

    static final String ID = "I10";
    static final String NAME = "Redis hold state disagrees with Postgres";
    /** Consumer lag tolerated at runtime (D-022 decision): an unapplied entry younger than this. */
    static final Duration LAG_WINDOW = Duration.ofSeconds(2);

    private final RedisHoldState redis;

    /** @param redis null when this context has no reader: REDIS_FIRST events then report "cannot check" */
    RedisConsistencyCheck(RedisHoldState redis) {
        this.redis = redis;
    }

    /** What phase 1 found. */
    sealed interface FirstRead {
        record NotRedisFirst() implements FirstRead {
        }

        record NoReader() implements FirstRead {
        }

        record NotLoaded() implements FirstRead {
        }

        record Failed(String message) implements FirstRead {
        }

        record Loaded(Snapshot snapshot) implements FirstRead {
        }
    }

    /** Phase 1: call BEFORE the Postgres snapshot is taken. Only REDIS_FIRST events touch Redis. */
    FirstRead readFirst(JdbcClient jdbc, UUID eventId) {
        String strategy = jdbc.sql("SELECT strategy FROM events WHERE id = ?").param(eventId)
                .query(String.class).optional().orElse(null);
        if (!"REDIS_FIRST".equals(strategy)) {
            return new FirstRead.NotRedisFirst();
        }
        if (redis == null) {
            return new FirstRead.NoReader();
        }
        try {
            return redis.read(eventId, LAG_WINDOW).<FirstRead>map(FirstRead.Loaded::new)
                    .orElse(new FirstRead.NotLoaded());
        } catch (RuntimeException e) {
            return new FirstRead.Failed(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /** What phase 2 found: the final answer, or the disagreements to read again from both stores. */
    sealed interface Compared {
        record Done(List<String> violations) implements Compared {
        }

        record ReadAgain(Snapshot firstRead, PostgresView snapshot, List<Diff> before) implements Compared {
        }
    }

    /** Phase 2, inside the checker's snapshot transaction: the first read against the snapshot. */
    Compared compareInSnapshot(JdbcClient jdbc, UUID eventId, FirstRead first) {
        return switch (first) {
            case FirstRead.NotRedisFirst ignored -> new Compared.Done(List.of());
            case FirstRead.NotLoaded ignored -> new Compared.Done(List.of());
            case FirstRead.NoReader ignored -> new Compared.Done(List.of("cannot check: the event is REDIS_FIRST but "
                    + "this context has no RedisHoldState reader"));
            case FirstRead.Failed failed -> new Compared.Done(List.of("cannot check: reading Redis failed: "
                    + failed.message()));
            case FirstRead.Loaded loaded -> {
                PostgresView pg = PostgresView.read(jdbc, eventId);
                List<Diff> before = diff(loaded.snapshot(), pg, loaded.snapshot().now());
                yield before.isEmpty() ? new Compared.Done(List.of())
                        : new Compared.ReadAgain(loaded.snapshot(), pg, before);
            }
        };
    }

    /**
     * Phase 3, AFTER the snapshot transaction: only what disagreed is read again, Redis first and then
     * Postgres ({@code readOnly}, its own short transaction), the same order as the first comparison
     * (D-022 M2.2d). Compared against the snapshot instead, a change that reached Postgres after the
     * snapshot and Redis before the re-read looked like drift: a lagging hold sold in between ("sold in
     * Redis, free in Postgres"), or a hold whose HELD entry the consumer applied and acknowledged in
     * between (no unapplied entry left to excuse it).
     */
    List<String> readAgainAndCompare(JdbcClient jdbc, TransactionOperations readOnly, UUID eventId, Duration grace,
                                     Compared compared) {
        if (compared instanceof Compared.Done done) {
            return done.violations();
        }
        Compared.ReadAgain first = (Compared.ReadAgain) compared;
        Set<UUID> seats = new LinkedHashSet<>();
        Set<UUID> holds = new LinkedHashSet<>();
        Set<UUID> users = new LinkedHashSet<>();
        first.before().forEach(d -> {
            seats.addAll(d.seats());
            holds.addAll(d.holds());
            users.addAll(d.users());
        });
        Optional<Snapshot> again;
        try {
            again = redis.reread(eventId, LAG_WINDOW, seats, holds, users);
        } catch (RuntimeException e) {
            return List.of("cannot check: re-reading Redis failed: " + e.getClass().getSimpleName() + ": "
                    + e.getMessage());
        }
        if (again.isEmpty()) {
            return List.of();   // unloaded between the reads (Redis lost it): like an event not loaded
        }
        Snapshot merged = merge(first.firstRead(), again.get(), seats, holds, users);
        Set<UUID> pgHolds = new HashSet<>(holds);
        pgHolds.addAll(namedBy(again.get()));
        PostgresView pg = readOnly.execute(status ->
                PostgresView.readAgain(jdbc, eventId, first.snapshot(), seats, pgHolds, users));

        Set<String> disagreed = first.before().stream().map(Diff::key).collect(Collectors.toSet());
        List<UnappliedEntry> entries = new ArrayList<>(first.firstRead().unappliedEntries());
        entries.addAll(again.get().unappliedEntries());
        Instant now = merged.now();

        List<String> violations = new ArrayList<>();
        for (Diff d : diff(merged, pg, now)) {
            if (!disagreed.contains(d.key())) {
                continue;  // only what disagreed in the first comparison was read again
            }
            if (excused(d, grace, entries, now, merged, pg)) {
                continue;
            }
            violations.add(d.text() + lagNote(d, entries, now));
        }
        return violations;
    }

    // --- tolerance -------------------------------------------------------------------------------

    private static boolean excused(Diff d, Duration grace, List<UnappliedEntry> entries, Instant now,
                                   Snapshot redisState, PostgresView pg) {
        if (grace.isZero() || d.holds().isEmpty()) {
            return false;
        }
        for (UUID hold : d.holds()) {
            boolean lag = entries.stream().anyMatch(e -> e.holdId().equals(hold)
                    && Duration.between(e.writtenAt(), now).compareTo(LAG_WINDOW) < 0);
            RedisHoldState.Hold inRedis = redisState.holds().get(hold);
            PgHold inPostgres = pg.holds().get(hold);
            boolean saleFinishing = inRedis != null && inRedis.pinned() && inPostgres != null
                    && inPostgres.status().equals("CONVERTED") && inPostgres.orderAgeMs() != null
                    && inPostgres.orderAgeMs() < grace.toMillis();
            if (!lag && !saleFinishing) {
                return false;
            }
        }
        return true;
    }

    private static String lagNote(Diff d, List<UnappliedEntry> entries, Instant now) {
        String notes = entries.stream()
                .filter(e -> d.holds().contains(e.holdId()))
                .map(e -> e.type() + " entry for " + e.holdId() + " unapplied, "
                        + Duration.between(e.writtenAt(), now).toMillis() + " ms old")
                .distinct()
                .collect(Collectors.joining("; "));
        return notes.isEmpty() ? "" : " (" + notes + ")";
    }

    // --- comparison ------------------------------------------------------------------------------

    /** One disagreement. {@code key} identifies it across the two comparisons. */
    record Diff(String key, Set<UUID> seats, Set<UUID> holds, Set<UUID> users, String text) {
    }

    static List<Diff> diff(Snapshot r, PostgresView pg, Instant now) {
        List<Diff> diffs = new ArrayList<>();

        if (r.perUserLimit() != pg.perUserLimit()) {
            diffs.add(new Diff("limit", Set.of(), Set.of(), Set.of(), "per-user limit is " + r.perUserLimit()
                    + " in Redis but " + pg.perUserLimit() + " in Postgres"));
        }
        if (!r.seatIds().equals(pg.seats().keySet())) {
            Set<UUID> onlyRedis = new TreeSet<>(r.seatIds());
            onlyRedis.removeAll(pg.seats().keySet());
            Set<UUID> onlyPostgres = new TreeSet<>(pg.seats().keySet());
            onlyPostgres.removeAll(r.seatIds());
            diffs.add(new Diff("seatset", Set.of(), Set.of(), Set.of(), "seat set differs: only in Redis "
                    + onlyRedis + ", only in Postgres " + onlyPostgres));
        }

        // Live holds.
        Set<UUID> holdIds = new TreeSet<>(r.holds().keySet());
        holdIds.addAll(pg.holds().keySet());
        for (UUID h : holdIds) {
            RedisHoldState.Hold rh = r.holds().get(h);
            PgHold ph = pg.holds().get(h);
            boolean redisLive = rh != null && rh.expiresAt().isAfter(now);
            boolean pgLive = ph != null && ph.status().equals("ACTIVE") && ph.expiresAt().isAfter(now);
            String key = "hold:" + h;
            if (redisLive && !pgLive) {
                diffs.add(new Diff(key, Set.of(), Set.of(h), Set.of(), "hold " + h + " is live in Redis (user "
                        + rh.userId() + ", seats " + sorted(rh.seatIds()) + ", expires " + rh.expiresAt()
                        + (rh.pinned() ? ", pinned" : "") + ") but Postgres has "
                        + (ph == null ? "no ACTIVE or CONVERTED row" : ph.status() + " expiring " + ph.expiresAt())));
            } else if (pgLive && !redisLive) {
                diffs.add(new Diff(key, Set.of(), Set.of(h), Set.of(), "hold " + h + " is ACTIVE in Postgres until "
                        + ph.expiresAt() + " but Redis " + (rh == null ? "doesn't have it"
                        : "has it expired at " + rh.expiresAt())));
            } else if (redisLive) {
                List<String> fields = new ArrayList<>();
                if (!rh.userId().equals(ph.userId())) {
                    fields.add("user " + rh.userId() + " vs " + ph.userId());
                }
                if (!new HashSet<>(rh.seatIds()).equals(new HashSet<>(ph.seatIds()))) {
                    fields.add("seats " + sorted(rh.seatIds()) + " vs " + sorted(ph.seatIds()));
                }
                if (!rh.expiresAt().equals(ph.expiresAt())) {
                    fields.add("expires " + rh.expiresAt() + " vs " + ph.expiresAt());
                }
                if (!rh.createdAt().equals(ph.createdAt())) {
                    fields.add("created " + rh.createdAt() + " vs " + ph.createdAt());
                }
                if (!fields.isEmpty()) {
                    diffs.add(new Diff(key, Set.of(), Set.of(h), Set.of(), "hold " + h
                            + " differs (Redis vs Postgres): " + String.join(", ", fields)));
                }
            }
        }

        // Seats.
        Set<UUID> seatIds = new TreeSet<>(r.seatIds());
        seatIds.addAll(pg.seats().keySet());
        seatIds.addAll(r.seatValues().keySet());
        for (UUID s : seatIds) {
            SeatState inRedis = redisSeat(r, s, now);
            SeatState inPostgres = pgSeat(pg, s, now);
            if (!inRedis.equals(inPostgres)) {
                Set<UUID> named = new HashSet<>();
                inRedis.hold().ifPresent(named::add);
                inPostgres.hold().ifPresent(named::add);
                // A key that still names a hold past its expiry (pinned by a sale) is "free" by time, but
                // hold.lua treats the seat as taken: name that hold too, so a sale finishing is excused for
                // it like any other (review finding L1). Whether there is a difference doesn't change.
                if (r.seatValues().get(s) instanceof SeatValue.Held held) {
                    named.add(held.holdId());
                }
                diffs.add(new Diff("seat:" + s, Set.of(s), named, Set.of(), "seat " + s + " is " + inRedis
                        + " in Redis but " + inPostgres + " in Postgres"));
            }
        }

        // Each user's cap.
        Set<UUID> userIds = new TreeSet<>(r.capMembers().keySet());
        pg.holds().values().forEach(h -> userIds.add(h.userId()));
        for (UUID u : userIds) {
            Set<String> inRedis = new TreeSet<>();
            for (CapMember m : r.capMembers().getOrDefault(u, List.of())) {
                if (capMemberLive(r, m, now)) {
                    inRedis.add(m.holdId() + ":" + m.seatCount());
                }
            }
            Set<String> inPostgres = new TreeSet<>();
            for (PgHold h : pg.holds().values()) {
                boolean counts = h.status().equals("CONVERTED")
                        || (h.status().equals("ACTIVE") && h.expiresAt().isAfter(now));
                if (h.userId().equals(u) && counts) {
                    inPostgres.add(h.id() + ":" + h.seatIds().size());
                }
            }
            if (!inRedis.equals(inPostgres)) {
                Set<String> different = new HashSet<>(inRedis);
                different.addAll(inPostgres);
                Set<String> common = new HashSet<>(inRedis);
                common.retainAll(inPostgres);
                different.removeAll(common);
                Set<UUID> named = different.stream()
                        .map(member -> UUID.fromString(member.substring(0, member.lastIndexOf(':'))))
                        .collect(Collectors.toSet());
                diffs.add(new Diff("user:" + u, Set.of(), named, Set.of(u), "user " + u + "'s cap counts "
                        + inRedis + " in Redis but " + inPostgres + " in Postgres (hold:seats)"));
            }
        }
        return diffs;
    }

    /** A seat, normalised by time. */
    record SeatState(String kind, UUID id) {
        Optional<UUID> hold() {
            return (kind.equals("held by") || kind.equals("named by a missing hold")) ? Optional.of(id)
                    : Optional.empty();
        }

        @Override
        public String toString() {
            return id == null ? kind : kind + " " + id;
        }
    }

    private static SeatState redisSeat(Snapshot r, UUID seat, Instant now) {
        if (!r.seatIds().contains(seat) && !r.seatValues().containsKey(seat)) {
            return new SeatState("missing", null);
        }
        SeatValue value = r.seatValues().get(seat);
        return switch (value) {
            case null -> new SeatState("free", null);
            case SeatValue.Sold sold -> new SeatState("sold to", sold.orderId());
            case SeatValue.Unreadable unreadable -> new SeatState("unreadable '" + unreadable.raw() + "'", null);
            case SeatValue.Held held -> {
                RedisHoldState.Hold hold = r.holds().get(held.holdId());
                if (hold == null) {
                    // The hash and its seat keys expire together and are deleted together: this can't happen.
                    yield new SeatState("named by a missing hold", held.holdId());
                }
                // An expired hold that a stalled sale pinned still has its key; by time, the seat is free.
                yield hold.expiresAt().isAfter(now) ? new SeatState("held by", held.holdId())
                        : new SeatState("free", null);
            }
        };
    }

    private static SeatState pgSeat(PostgresView pg, UUID seat, Instant now) {
        PgSeat row = pg.seats().get(seat);
        if (row == null) {
            return new SeatState("missing", null);
        }
        return switch (row.status()) {
            case "SOLD" -> new SeatState("sold to", row.orderId());
            case "HELD" -> {
                PgHold hold = pg.holds().get(row.holdId());
                boolean expired = hold != null && hold.status().equals("ACTIVE") && !hold.expiresAt().isAfter(now);
                yield expired ? new SeatState("free", null) : new SeatState("held by", row.holdId());
            }
            default -> new SeatState("free", null);
        };
    }

    private static boolean capMemberLive(Snapshot r, CapMember m, Instant now) {
        if (m.countsUntil() != null && !m.countsUntil().isAfter(now)) {
            return false;
        }
        // +inf with the hash still there = pinned by a sale: it counts only while the hold hasn't expired.
        RedisHoldState.Hold hold = r.holds().get(m.holdId());
        return hold == null || hold.expiresAt().isAfter(now);
    }

    /** The first read with the re-read keys replaced by their new values (absent = gone). */
    static Snapshot merge(Snapshot first, Snapshot again, Set<UUID> seats, Set<UUID> holds, Set<UUID> users) {
        Map<UUID, SeatValue> seatValues = new HashMap<>(first.seatValues());
        seats.forEach(seatValues::remove);
        seatValues.putAll(again.seatValues());

        // The re-read also read every hold its seats and users name; those replace the first read's too.
        Set<UUID> reread = new HashSet<>(holds);
        reread.addAll(namedBy(again));
        Map<UUID, RedisHoldState.Hold> holdMap = new HashMap<>(first.holds());
        reread.forEach(holdMap::remove);
        holdMap.putAll(again.holds());

        Map<UUID, List<CapMember>> capMembers = new HashMap<>(first.capMembers());
        users.forEach(capMembers::remove);
        capMembers.putAll(again.capMembers());

        return new Snapshot(again.now(), again.perUserLimit(), again.seatIds(), seatValues, holdMap, capMembers,
                again.unappliedEntries());
    }

    /** The holds a re-read's seat values and cap members name (the re-read read those holds too). */
    private static Set<UUID> namedBy(Snapshot again) {
        Set<UUID> named = new HashSet<>();
        again.seatValues().values().forEach(v -> {
            if (v instanceof SeatValue.Held held) {
                named.add(held.holdId());
            }
        });
        again.capMembers().values().forEach(ms -> ms.forEach(m -> named.add(m.holdId())));
        return named;
    }

    private static List<UUID> sorted(List<UUID> ids) {
        return ids.stream().sorted(Comparator.comparing(UUID::toString)).toList();
    }

    // --- Postgres side ---------------------------------------------------------------------------

    record PgSeat(String status, UUID holdId, UUID orderId) {
    }

    /** An ACTIVE or CONVERTED hold. {@code orderAgeMs}: age of its newest order by Postgres' clock, or null. */
    record PgHold(UUID id, UUID userId, List<UUID> seatIds, Instant createdAt, Instant expiresAt, String status,
                  Long orderAgeMs) {
    }

    record PostgresView(int perUserLimit, Map<UUID, PgSeat> seats, Map<UUID, PgHold> holds) {

        static PostgresView read(JdbcClient jdbc, UUID eventId) {
            int limit = jdbc.sql("SELECT per_user_limit FROM events WHERE id = ?").param(eventId)
                    .query(Integer.class).single();
            Map<UUID, PgSeat> seats = new HashMap<>();
            jdbc.sql("SELECT seat_id, status, hold_id, order_id FROM event_seats WHERE event_id = ?")
                    .param(eventId)
                    .query(rs -> {
                        seats.put(rs.getObject("seat_id", UUID.class), new PgSeat(rs.getString("status"),
                                rs.getObject("hold_id", UUID.class), rs.getObject("order_id", UUID.class)));
                    });
            Map<UUID, PgHold> holds = new HashMap<>();
            readHolds(jdbc.sql(HOLDS + "WHERE h.event_id = :event AND h.status IN ('ACTIVE', 'CONVERTED')")
                    .param("event", eventId), holds);
            return new PostgresView(limit, seats, holds);
        }

        /**
         * {@code base} (the snapshot's view) with the listed keys read again, now (D-022 M2.2d): those seats,
         * the holds listed or named by those seats, and every ACTIVE or CONVERTED hold of those users. Call
         * it in one read-only transaction so the rows read again agree with each other.
         */
        static PostgresView readAgain(JdbcClient jdbc, UUID eventId, PostgresView base, Set<UUID> seatIds,
                                      Set<UUID> holdIds, Set<UUID> userIds) {
            int limit = jdbc.sql("SELECT per_user_limit FROM events WHERE id = ?").param(eventId)
                    .query(Integer.class).single();
            Map<UUID, PgSeat> seats = new HashMap<>(base.seats());
            if (!seatIds.isEmpty()) {
                jdbc.sql("""
                        SELECT seat_id, status, hold_id, order_id FROM event_seats
                        WHERE event_id = :event AND seat_id = ANY(:seats)
                        """)
                        .param("event", eventId)
                        .param("seats", seatIds.toArray(UUID[]::new))
                        .query(rs -> {
                            seats.put(rs.getObject("seat_id", UUID.class), new PgSeat(rs.getString("status"),
                                    rs.getObject("hold_id", UUID.class), rs.getObject("order_id", UUID.class)));
                        });
            }
            Set<UUID> ids = new HashSet<>(holdIds);
            seatIds.forEach(seat -> {
                PgSeat row = seats.get(seat);
                if (row != null && row.holdId() != null) {
                    ids.add(row.holdId());
                }
            });
            // Replaced, not added to: a hold read again that is no longer ACTIVE or CONVERTED is gone.
            Map<UUID, PgHold> holds = new HashMap<>(base.holds());
            holds.values().removeIf(h -> ids.contains(h.id()) || userIds.contains(h.userId()));
            readHolds(jdbc.sql(HOLDS + """
                    WHERE h.event_id = :event AND h.status IN ('ACTIVE', 'CONVERTED')
                      AND (h.id = ANY(:ids) OR h.user_id = ANY(:users))
                    """)
                    .param("event", eventId)
                    .param("ids", ids.toArray(UUID[]::new))
                    .param("users", userIds.toArray(UUID[]::new)), holds);
            return new PostgresView(limit, seats, holds);
        }

        private static final String HOLDS = """
                SELECT h.id, h.user_id, h.seat_ids, h.created_at, h.expires_at, h.status,
                       (SELECT round(extract(epoch FROM now() - max(o.created_at)) * 1000)::bigint
                        FROM orders o WHERE o.hold_id = h.id) AS order_age_ms
                FROM holds h
                """;

        private static void readHolds(JdbcClient.StatementSpec query, Map<UUID, PgHold> into) {
            query.query(rs -> {
                UUID id = rs.getObject("id", UUID.class);
                into.put(id, new PgHold(id, rs.getObject("user_id", UUID.class), uuids(rs.getArray("seat_ids")),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("expires_at", OffsetDateTime.class).toInstant(), rs.getString("status"),
                        rs.getObject("order_age_ms", Long.class)));
            });
        }

        private static List<UUID> uuids(Array array) throws SQLException {
            return Arrays.asList((UUID[]) array.getArray());
        }
    }
}
