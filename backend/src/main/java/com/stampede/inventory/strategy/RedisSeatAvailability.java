package com.stampede.inventory.strategy;

import com.stampede.inventory.strategy.RedisHoldStore.EventEpoch;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/**
 * Which seats of a REDIS_FIRST event Redis has taken, for the seat map (M2.4a-seatmap, D-020, D-022).
 * <p>
 * Under REDIS_FIRST, Redis decides every hold; Postgres learns of a hold through the stream consumer, a
 * little later (seconds under a drop: M2.4a-seatmap measured the oldest unapplied entry at 3.7-5.6 s). A
 * seat map read from Postgres showed new holds as free for that long, and holds that expired by their
 * TTL as taken until the sweeper's next tick. Here a seat is taken when its Redis key exists:
 * {@code H:<hold>} (held, or pinned by a sale in progress) or {@code S:<order>} (sold). A hold that
 * expires by TTL needs no script: Redis never returns an expired key, so it reads as free from its expiry
 * millisecond.
 * <p>
 * <b>The read</b>: {@code epoch.lua} (Redis' clock, loaded or closed, the load epoch), the seat keys in
 * MGETs of {@value #CHUNK} keys in the caller's order, then {@code epoch.lua} again. Each MGET blocks
 * Redis for well under a millisecond (measured: about 0.26 ms for 256 held seats), so a buyer's
 * {@code hold.lua} never waits long behind a seat map. The chunks are not one snapshot: a hold made
 * mid-read can show half its seats. The seat map is advisory (D-020: Redis still decides every hold), so
 * that costs at most one wasted attempt.
 * <p>
 * <b>Unavailable</b>, and the caller answers from Postgres instead, when the event isn't loaded (Redis
 * restarted and hasn't reloaded it: every seat key would be missing, and every seat would look free), its
 * gate is closed (a strategy switch, D-023), its load epoch changed during the read (wiped and reloaded
 * mid-read), or Redis failed. Never an exception for those: a stale seat map is better than none.
 */
@Component
public class RedisSeatAvailability {

    /** Seat keys per MGET (decided 2026-11-19): 8 commands for the 2,014-seat demo event. */
    static final int CHUNK = 256;

    private static final Logger log = LoggerFactory.getLogger(RedisSeatAvailability.class);

    private final RedisHoldStore store;
    /** Test hook only: a smaller chunk exercises several MGETs on a small event. */
    private volatile int chunkSize = CHUNK;
    /** Test hook only: runs between two MGETs. */
    private volatile Runnable betweenChunks = () -> { };

    RedisSeatAvailability(RedisHoldStore store) {
        this.store = store;
    }

    /** The result of {@link #read}. */
    public sealed interface Read {

        /**
         * @param taken one entry per requested seat, in the requested order: true if Redis has its key
         * @param asOf  Redis' clock when the read started: the clock that decides REDIS_FIRST expiry
         */
        record Fresh(List<Boolean> taken, Instant asOf) implements Read {
            public Fresh {
                taken = List.copyOf(taken);
            }
        }

        /** Redis can't answer for this event right now; read Postgres. */
        record Unavailable(Reason reason) implements Read {
        }
    }

    /** Why Redis couldn't answer. */
    public enum Reason {
        /** Redis doesn't have the event (no meta): a restart before the reload, or never loaded. */
        NOT_LOADED,
        /** The event's gate is closed: a strategy switch is under way or done (D-023). */
        CLOSED,
        /** The load epoch changed during the read: Redis was wiped and the event reloaded mid-read. */
        RELOADED,
        /** A Redis command failed (unreachable, timeout). */
        ERROR
    }

    /** Which of these seats (an event's, in any order) Redis has taken. See the class comment. */
    public Read read(UUID eventId, List<UUID> seatIds) {
        try {
            EventEpoch before = store.eventEpoch(eventId);
            if (!before.state().equals("LOADED")) {
                return new Read.Unavailable(reason(before));
            }
            List<Boolean> taken = new ArrayList<>(seatIds.size());
            int chunk = chunkSize;
            for (int from = 0; from < seatIds.size(); from += chunk) {
                if (from > 0) {
                    betweenChunks.run();
                }
                for (String value : store.seatValues(eventId, seatIds.subList(from, Math.min(from + chunk, seatIds.size())))) {
                    taken.add(value != null);
                }
            }
            EventEpoch after = store.eventEpoch(eventId);
            if (!after.state().equals("LOADED")) {
                return new Read.Unavailable(reason(after));
            }
            if (!after.loadedMs().equals(before.loadedMs())) {
                return new Read.Unavailable(Reason.RELOADED);
            }
            return new Read.Fresh(taken, Instant.ofEpochMilli(before.nowMillis()));
        } catch (DataAccessException e) {
            // Only Redis failures: a bug (IllegalStateException) still fails the request.
            log.warn("Seat map of event {}: Redis read failed, answering from Postgres: {}", eventId, e.toString());
            return new Read.Unavailable(Reason.ERROR);
        }
    }

    private static Reason reason(EventEpoch epoch) {
        return epoch.state().equals("CLOSED") ? Reason.CLOSED : Reason.NOT_LOADED;
    }

    void chunkSize(int size) {
        this.chunkSize = size;
    }

    void betweenChunks(Runnable hook) {
        this.betweenChunks = hook;
    }
}
