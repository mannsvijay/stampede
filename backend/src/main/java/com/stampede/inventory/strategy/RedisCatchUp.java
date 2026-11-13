package com.stampede.inventory.strategy;

import com.stampede.inventory.strategy.RedisHoldStore.LiveHold;
import com.stampede.inventory.strategy.StreamEntry.HoldFacts;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Brings Postgres up to date with Redis for ONE hold, on the request path (Redis-first, D-022, M2.2c).
 * <p>
 * A REDIS_FIRST hold reaches Postgres through the stream consumer, a little later than the buyer is told
 * "held". Code that reads the {@code holds} row (checkout step 0, cancel, the retry rule of
 * POST /events/{id}/holds) would otherwise see nothing during that lag. Catch-up does for one hold
 * what the consumer will do anyway: if Redis has the hold and Postgres doesn't, it persists it through
 * {@link RedisHoldWriter#applyHeld} (the same idempotent "roll forward" as reconciler rule R1). When the
 * consumer's HELD entry arrives later it finds the row and changes nothing.
 * <p>
 * Only this hold, never the whole stream: the cost is one or two Redis reads, however far behind the
 * consumer is.
 */
@Component
public class RedisCatchUp {

    private final JdbcClient jdbc;
    private final RedisHoldStore store;
    private final RedisHoldWriter writer;
    private final RedisEventLoader loader;

    RedisCatchUp(JdbcClient jdbc, RedisHoldStore store, RedisHoldWriter writer, RedisEventLoader loader) {
        this.jdbc = jdbc;
        this.store = store;
        this.writer = writer;
        this.loader = loader;
    }

    /** A live Redis hold of the caller, as the retry rule returns it. */
    public record OwnHold(UUID holdId, List<UUID> seatIds, Instant expiresAt) {
        public OwnHold {
            seatIds = List.copyOf(seatIds);
        }
    }

    /**
     * For a hold id whose event isn't known yet (POST /orders and DELETE /holds/{id} only carry the hold
     * id): if Postgres has no row for it, looks for it in each open REDIS_FIRST event in Redis and
     * persists it. Does nothing for a hold Postgres already has, or one Redis doesn't have.
     */
    public void catchUp(UUID holdId) {
        if (hasRow(holdId)) {
            return;
        }
        for (UUID eventId : loader.openRedisEvents()) {
            Optional<LiveHold> live = store.liveHold(eventId, holdId);
            if (live.isPresent()) {
                writer.applyHeld(HoldFacts.of(eventId, live.get()));
                return;
            }
        }
    }

    /**
     * Before the strategy releases or sells: loads the event if Redis doesn't have it (a restart; the
     * loader restores unexpired ACTIVE holds from Postgres), then persists the hold if only Redis has it.
     */
    void catchUp(UUID eventId, UUID holdId) {
        loader.ensureLoaded(eventId);
        if (hasRow(holdId)) {
            return;
        }
        store.liveHold(eventId, holdId).ifPresent(live -> writer.applyHeld(HoldFacts.of(eventId, live)));
    }

    /**
     * The retry rule of POST /events/{id}/holds for a REDIS_FIRST event (D-016): the caller's own live
     * Redis hold covering exactly these seats, if there is one. Redis decides, not Postgres: Postgres may
     * not have the hold yet (consumer lag), or may still show one that Redis has already expired.
     * The hold is persisted before it is returned, so the caller can go on to check out with it.
     */
    public Optional<OwnHold> ownLiveHoldOnExactly(UUID eventId, UUID userId, List<UUID> seatIds) {
        Optional<LiveHold> live = liveHoldOnExactly(eventId, userId, seatIds);
        if (live.isEmpty()) {
            return Optional.empty();
        }
        if (!hasRow(live.get().holdId())) {
            writer.applyHeld(HoldFacts.of(eventId, live.get()));
        }
        // Persisting can still lose to Postgres (a conflict releases the hold in Redis): ask again.
        return liveHoldOnExactly(eventId, userId, seatIds)
                .map(hold -> new OwnHold(hold.holdId(), hold.seatIds(), hold.expiresAt()));
    }

    private Optional<LiveHold> liveHoldOnExactly(UUID eventId, UUID userId, List<UUID> seatIds) {
        // Every seat of one hold names that hold, so the first seat's key says which hold to look at.
        Optional<UUID> holder = store.holderOf(eventId, seatIds.get(0));
        return holder.flatMap(holdId -> store.liveHold(eventId, holdId))
                .filter(hold -> hold.userId().equals(userId))
                .filter(hold -> new HashSet<>(hold.seatIds()).equals(new HashSet<>(seatIds)))
                // A hash past its expiry is a pinned hold whose sale never finished: not a live hold.
                .filter(hold -> hold.expiresAt().toEpochMilli() > store.nowMillis());
    }

    private boolean hasRow(UUID holdId) {
        return jdbc.sql("SELECT count(*) FROM holds WHERE id = :holdId")
                .param("holdId", holdId)
                .query(Long.class)
                .single() > 0;
    }
}
