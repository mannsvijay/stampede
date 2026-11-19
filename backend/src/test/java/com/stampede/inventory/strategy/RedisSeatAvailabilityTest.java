package com.stampede.inventory.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.stampede.inventory.strategy.RedisSeatAvailability.Read;
import com.stampede.inventory.strategy.RedisSeatAvailability.Reason;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisCallback;

/**
 * {@link RedisSeatAvailability}, the Redis read behind the REDIS_FIRST seat map (M2.4a-seatmap, D-020),
 * against a real Redis. SeatMapApiTest covers the endpoint; this covers what HTTP can't set up: several
 * MGETs on a small event (the chunk size is a test hook), a pinned hold, a real TTL expiry, a reload or a
 * Redis failure between two MGETs, and the cost on a 2,000-seat event.
 */
class RedisSeatAvailabilityTest extends RedisPersistenceTestSupport {

    @Autowired
    RedisSeatAvailability availability;

    @AfterEach
    void resetHooks() {
        availability.chunkSize(RedisSeatAvailability.CHUNK);
        availability.betweenChunks(() -> { });
    }

    @Test
    void eachSeatIsTakenExactlyWhenRedisHasItsKeyInTheRequestedOrderAcrossChunks() {
        sellDirectly(OTHER_USER, SEATS.get(20));   // before the load: loaded as an S: marker
        load();
        hold(USER, FIVE_MINUTES, SEATS.get(3), SEATS.get(7));
        hold(OTHER_USER, FIVE_MINUTES, SEATS.get(11));
        availability.chunkSize(5);                  // 24 seats: five MGETs
        List<UUID> order = new ArrayList<>(SEATS);
        Collections.reverse(order);

        Read read = availability.read(EVENT, order);

        List<Boolean> expected = order.stream().map(seat -> store.seatValue(EVENT, seat) != null).toList();
        assertThat(expected).as("three held seats and one sold").filteredOn(taken -> taken).hasSize(4);
        assertThat(read).isInstanceOfSatisfying(Read.Fresh.class,
                fresh -> assertThat(fresh.taken()).isEqualTo(expected));
        drain();   // as the consumer would: both stores agree again
    }

    @Test
    void aPinnedHoldStaysTakenAndAHoldExpiredByItsTtlIsFree() {
        load();
        UUID pinned = hold(USER, FIVE_MINUTES, SEATS.get(0));
        assertThat(store.pin(EVENT, pinned)).isInstanceOf(RedisHoldStore.Pin.Pinned.class);   // a sale in progress
        UUID expiring = hold(OTHER_USER, ONE_SECOND, SEATS.get(1));
        waitForRedisExpiry(expiring);   // no script runs: the TTL ends it

        Read read = availability.read(EVENT, List.of(SEATS.get(0), SEATS.get(1)));

        assertThat(read).isInstanceOfSatisfying(Read.Fresh.class,
                fresh -> assertThat(fresh.taken()).containsExactly(true, false));
        drain();
    }

    @Test
    void asOfIsRedisClockWhenTheReadStarted() {
        load();
        long before = store.nowMillis();

        Read read = availability.read(EVENT, SEATS);

        long after = store.nowMillis();
        assertThat(read).isInstanceOfSatisfying(Read.Fresh.class, fresh -> assertThat(fresh.asOf())
                .isBetween(Instant.ofEpochMilli(before), Instant.ofEpochMilli(after)));
    }

    @Test
    void anEventRedisHasNotLoadedIsUnavailableNotAllFree() {
        assertThat(availability.read(EVENT, SEATS)).isEqualTo(new Read.Unavailable(Reason.NOT_LOADED));
    }

    @Test
    void anEventWithItsGateClosedIsUnavailable() {
        load();
        store.close(EVENT);

        assertThat(availability.read(EVENT, SEATS)).isEqualTo(new Read.Unavailable(Reason.CLOSED));
    }

    @Test
    void aGateClosedDuringTheReadMakesItUnavailable() {
        load();
        availability.chunkSize(5);
        availability.betweenChunks(() -> store.close(EVENT));

        assertThat(availability.read(EVENT, SEATS)).isEqualTo(new Read.Unavailable(Reason.CLOSED));
    }

    @Test
    void aWipeAndReloadDuringTheReadMakesItUnavailable() {
        load();
        UUID hold = hold(USER, FIVE_MINUTES, SEATS.get(0));
        availability.chunkSize(5);
        availability.betweenChunks(() -> {
            // Redis lost everything and the event was loaded again (a new epoch) between two MGETs.
            long epoch = store.nowMillis();
            store.deleteEventKeys(EVENT);
            org.awaitility.Awaitility.await().until(() -> store.nowMillis() > epoch);
            assertThat(loader.ensureLoaded(EVENT)).isTrue();
            availability.betweenChunks(() -> { });
        });

        assertThat(availability.read(EVENT, SEATS)).isEqualTo(new Read.Unavailable(Reason.RELOADED));
        assertThat(hold).isNotNull();
    }

    @Test
    void aRedisFailureMakesItUnavailableInsteadOfFailingTheSeatMap() {
        load();
        availability.chunkSize(5);
        availability.betweenChunks(() -> {
            throw new RedisConnectionFailureException("simulated: Redis unreachable");
        });

        assertThat(availability.read(EVENT, SEATS)).isEqualTo(new Read.Unavailable(Reason.ERROR));
    }

    /**
     * The cost on the demo event's size (decision 2, MGET in chunks of 256): 2,000 seats all held (the
     * largest replies), 10 reads with SLOWLOG logging every command. Printed for D-020; the bound is loose
     * so CI noise can't fail the test. Measured the same way as the M2.2d I10 read (RedisHoldStateReaderTest).
     */
    @Test
    @SuppressWarnings("unchecked")
    void howLongASeatMapReadBlocksRedisOnA2000SeatEventWithEverySeatHeld() {
        UUID event = loadTimingEvent();
        List<UUID> seats = jdbc.sql("SELECT seat_id FROM event_seats WHERE event_id = ? ORDER BY seat_id")
                .param(event).query(UUID.class).list();
        assertThat(availability.read(event, seats)).isInstanceOf(Read.Fresh.class);   // loads the script: not measured

        String previousThreshold = config("slowlog-log-slower-than", "10000");
        String previousLength = config("slowlog-max-len", "128");
        List<Long> longestPerRead = new ArrayList<>();
        List<Long> totalPerRead = new ArrayList<>();
        List<Long> readMicros = new ArrayList<>();
        int commands = 0;
        java.util.Map<String, Integer> commandNames = new java.util.TreeMap<>();
        try {
            setConfig("slowlog-max-len", "100000");
            setConfig("slowlog-log-slower-than", "0");
            for (int run = 0; run < 10; run++) {
                slowlog(server -> server.slowlogReset());
                long start = System.nanoTime();
                Read read = availability.read(event, seats);
                readMicros.add((System.nanoTime() - start) / 1_000);
                assertThat(read).isInstanceOfSatisfying(Read.Fresh.class,
                        fresh -> assertThat(fresh.taken()).hasSize(2000).containsOnly(true));
                long longest = 0;
                long total = 0;
                commands = 0;
                commandNames.clear();
                // Each entry: id, unix time, execution time in microseconds, the command's arguments, ...
                for (Object entry : (List<Object>) slowlog(server -> server.slowlogGet(100000))) {
                    List<Object> fields = (List<Object>) entry;
                    Object first = ((List<Object>) fields.get(3)).get(0);
                    String name = (first instanceof byte[] bytes ? new String(bytes) : String.valueOf(first)).toUpperCase();
                    if (name.startsWith("SLOWLOG")) {
                        continue;
                    }
                    long micros = (Long) fields.get(2);
                    longest = Math.max(longest, micros);
                    total += micros;
                    commands++;
                    commandNames.merge(name, 1, Integer::sum);
                }
                longestPerRead.add(longest);
                totalPerRead.add(total);
            }
        } finally {
            setConfig("slowlog-log-slower-than", previousThreshold);
            setConfig("slowlog-max-len", previousLength);
            slowlog(server -> server.slowlogReset());
        }
        System.out.println("[seat map Redis read] 2000 seats, all held, chunks of " + RedisSeatAvailability.CHUNK
                + ", " + commands + " commands per read " + commandNames + ". Longest single command per read (us, 10 reads): "
                + sorted(longestPerRead) + ". Redis time per read, all commands (us): " + sorted(totalPerRead)
                + ". Read time on the client (us, SLOWLOG on): " + sorted(readMicros));

        assertThat(sorted(longestPerRead).get(5)).as("median longest command, us").isLessThan(100_000);
    }

    private static List<Long> sorted(List<Long> values) {
        return values.stream().sorted().toList();
    }

    /** The demo event's size in Postgres and Redis: 2,000 seats, all held by 500 buyers, 4 seats each. */
    private UUID loadTimingEvent() {
        UUID event = id("seatmap-timing-event");
        jdbc.sql("""
                INSERT INTO users (id, email, password_hash)
                SELECT md5('seatmap-timing-user' || k)::uuid, 'seatmap-timing' || k || '@example.test', 'x'
                FROM generate_series(1, 500) k
                """).update();
        jdbc.sql("""
                INSERT INTO seats (id, section_id, row_label, number, x, y)
                SELECT md5('seatmap-timing-seat' || g)::uuid, :section, 'M', g, 0, 0 FROM generate_series(1, 2000) g
                """).param("section", SECTION).update();
        jdbc.sql("""
                INSERT INTO events (id, venue_id, title, starts_at, drop_at, status, per_user_limit, strategy)
                VALUES (?, ?, 'Seat map timing', now() + interval '30 days', now() - interval '1 hour', 'LIVE', 4,
                        'REDIS_FIRST')
                """).params(event, VENUE).update();
        UUID tier = id("seatmap-timing-tier");
        jdbc.sql("INSERT INTO price_tiers (id, event_id, name, price_paise) VALUES (?, ?, 'GOLD', 150000)")
                .params(tier, event).update();
        jdbc.sql("""
                INSERT INTO holds (id, event_id, user_id, seat_ids, created_at, expires_at, status)
                SELECT md5('seatmap-timing-hold' || k)::uuid, :event, md5('seatmap-timing-user' || k)::uuid,
                       ARRAY(SELECT md5('seatmap-timing-seat' || g)::uuid FROM generate_series(4 * k - 3, 4 * k) g),
                       date_trunc('milliseconds', now()), date_trunc('milliseconds', now()) + interval '5 minutes',
                       'ACTIVE'
                FROM generate_series(1, 500) k
                """).param("event", event).update();
        jdbc.sql("""
                INSERT INTO event_seats (event_id, seat_id, tier_id, status, hold_id, version)
                SELECT :event, md5('seatmap-timing-seat' || g)::uuid, :tier, 'HELD',
                       md5('seatmap-timing-hold' || ((g - 1) / 4 + 1))::uuid, 1
                FROM generate_series(1, 2000) g
                """).param("event", event).param("tier", tier).update();
        jdbc.sql("""
                INSERT INTO user_event_allowance (event_id, user_id, used)
                SELECT :event, md5('seatmap-timing-user' || k)::uuid, 4 FROM generate_series(1, 500) k
                """).param("event", event).update();
        assertThat(loader.ensureLoaded(event)).isTrue();
        return event;
    }

    private String config(String name, String fallback) {
        Properties config = redis.execute((RedisCallback<Properties>) c -> c.serverCommands().getConfig(name));
        return config == null ? fallback : config.getProperty(name, fallback);
    }

    private void setConfig(String name, String value) {
        redis.execute((RedisCallback<Object>) c -> {
            c.serverCommands().setConfig(name, value);
            return null;
        });
    }

    /** SLOWLOG through Lettuce itself: Spring Data Redis has no API for it. */
    private Object slowlog(Function<io.lettuce.core.api.async.RedisServerAsyncCommands<byte[], byte[]>,
            io.lettuce.core.RedisFuture<?>> command) {
        return redis.execute((RedisCallback<Object>) c -> {
            @SuppressWarnings("unchecked")
            var server = (io.lettuce.core.api.async.RedisServerAsyncCommands<byte[], byte[]>) c.getNativeConnection();
            try {
                return command.apply(server).get(5, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
    }
}
