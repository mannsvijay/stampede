package com.stampede.inventory.strategy;

import com.stampede.inventory.strategy.HoldClosing.CloseDecision;
import com.stampede.inventory.strategy.HoldClosing.LockedHold;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Strategy C (PRD 6.2, D-022): a Lua script takes the seats in Redis, and Postgres catches up.
 * <ul>
 *   <li><b>hold</b> touches only Redis ({@code hold.lua}: all-or-nothing, cap recounted, TTL, stream
 *       entry). The stream consumer persists it ({@link RedisStreamConsumer}). This is the fast path the
 *       M2.5 benchmark compares.</li>
 *   <li><b>release and sell</b> start from an existing hold and work like the Postgres strategies:
 *       lock the {@code holds} row first, decide (here: run the Lua script, under that lock), then write
 *       Postgres in the same transaction. So a sale, a cancel and the sweeper for the same hold take turns
 *       on the row lock, and the checkout's compensating release gives a definitive answer (D-017).</li>
 * </ul>
 * <b>A sale in three steps</b>, so a sale never exists in Redis without Postgres:
 * <ol>
 *   <li>{@code pin.lua}, under the row lock: the hold stops expiring in Redis and its seats count in the
 *       cap for good. Redis no longer has it: HoldExpired, nothing written.</li>
 *   <li>Postgres: seats SOLD through {@code EventSeat}, hold CONVERTED, commit.</li>
 *   <li>After the commit, {@code sold.lua}: {@code S:} markers with no expiry, member +inf, hold hash
 *       deleted. If this fails the sale still stands (Postgres is the record); the seats stay taken in
 *       Redis (pinned) and reconciler rule R4 finishes it.</li>
 * </ol>
 * If step 2 fails, the pinned hold is freed by the checkout's compensating release (release.lua finds
 * the hash), or after a crash by the sweeper once its time has run out (release.lua's EXPIRED case).
 */
@Component
class RedisFirstInventoryStrategy implements InventoryStrategy {

    private static final Logger log = LoggerFactory.getLogger(RedisFirstInventoryStrategy.class);

    private final RedisHoldStore store;
    private final RedisEventLoader loader;
    private final RedisCatchUp catchUp;
    private final RedisHoldWriter writer;
    private final HoldClosing closing;
    private final HoldMetrics metrics;

    RedisFirstInventoryStrategy(RedisHoldStore store, RedisEventLoader loader, RedisCatchUp catchUp,
                                RedisHoldWriter writer, HoldClosing closing, HoldMetrics metrics) {
        this.store = store;
        this.loader = loader;
        this.catchUp = catchUp;
        this.writer = writer;
        this.closing = closing;
        this.metrics = metrics;
    }

    @Override
    public StrategyKind kind() {
        return StrategyKind.REDIS_FIRST;
    }

    /**
     * One script. If Redis doesn't have the event (first hold after a restart), loads it from Postgres
     * and runs the script once more; {@code attempts} then counts two.
     */
    @Override
    public HoldOutcome hold(HoldRequest request) {
        long start = System.nanoTime();
        HoldOutcome outcome = null;
        try {
            metrics.attempt(kind());
            Optional<HoldOutcome> result = store.tryHold(request);
            if (result.isEmpty()) {
                loader.ensureLoaded(request.eventId());
                metrics.attempt(kind());
                result = store.tryHold(request);
            }
            outcome = result.orElseThrow(() -> new IllegalStateException(
                    "Event " + request.eventId() + " is still not loaded into Redis after loading it"));
            return outcome;
        } finally {
            metrics.finished(kind(), outcome, System.nanoTime() - start);
        }
    }

    @Override
    public ReleaseOutcome release(UUID eventId, UUID holdId, ReleaseReason reason) {
        if (reason == ReleaseReason.EXPIRED) {
            return writer.expire(eventId, holdId);   // the sweeper; Redis' clock decides (D-022)
        }
        catchUp.catchUp(eventId, holdId);
        return closing.closeWith(eventId, holdId, hold -> releaseInRedis(hold, reason));
    }

    /**
     * Under the hold row lock: release.lua. GONE means Redis already ended the hold (its TTL ran out, or
     * a release whose Postgres write failed): Postgres records that, and the caller hears NotActive.
     * GONE while Redis was wiped or reloaded means nothing yet: load and ask once more.
     */
    private CloseDecision releaseInRedis(LockedHold hold, ReleaseReason reason) {
        for (int attempt = 1; ; attempt++) {
            Optional<String> epoch = loadedEpoch(hold.eventId());
            switch (store.release(hold.eventId(), hold.holdId(), reason)) {
                case RedisHoldStore.Release.Released released -> {
                    return new CloseDecision.Close(reason, false);
                }
                case RedisHoldStore.Release.NotExpired notExpired ->
                        throw new IllegalStateException("release.lua answered NOT_EXPIRED for reason " + reason);
                case RedisHoldStore.Release.Gone gone -> {
                    if (epoch.equals(store.loadEpoch(hold.eventId()))) {
                        return new CloseDecision.Close(writer.reasonForGoneHold(hold.expiresAt()), true);
                    }
                    if (attempt == 2) {
                        throw new IllegalStateException("Event " + hold.eventId()
                                + " kept being reloaded into Redis while releasing hold " + hold.holdId());
                    }
                }
            }
        }
    }

    @Override
    public SellOutcome sell(UUID eventId, UUID holdId, UUID orderId) {
        catchUp.catchUp(eventId, holdId);
        // Steps 1 and 2: pin under the row lock, then the Postgres sale, in one transaction.
        SellOutcome outcome = closing.sellWith(eventId, holdId, orderId, this::pinInRedis);
        if (outcome instanceof SellOutcome.Sold) {
            finishInRedis(eventId, holdId, orderId);   // step 3, after the commit
        }
        return outcome;
    }

    /** Step 1, under the hold row lock: empty = go ahead and sell in Postgres. */
    private Optional<SellOutcome> pinInRedis(LockedHold hold) {
        for (int attempt = 1; ; attempt++) {
            Optional<String> epoch = loadedEpoch(hold.eventId());
            switch (store.pin(hold.eventId(), hold.holdId())) {
                case RedisHoldStore.Pin.Pinned pinned -> {
                    return Optional.empty();
                }
                case RedisHoldStore.Pin.Expired expired -> {
                    return Optional.of(new SellOutcome.HoldExpired(expired.expiresAt()));
                }
                case RedisHoldStore.Pin.Gone gone -> {
                    // Redis ended the hold; the sweeper (or the consumer) closes the Postgres row.
                    if (epoch.equals(store.loadEpoch(hold.eventId()))) {
                        return Optional.of(new SellOutcome.HoldExpired(hold.expiresAt()));
                    }
                    if (attempt == 2) {
                        throw new IllegalStateException("Event " + hold.eventId()
                                + " kept being reloaded into Redis while selling hold " + hold.holdId());
                    }
                }
            }
        }
    }

    /**
     * Step 3. The sale is committed in Postgres, so nothing here may undo it or fail the call: one retry,
     * then a warning, and reconciler rule R4 finishes it (the seats stay taken in Redis meanwhile).
     */
    private void finishInRedis(UUID eventId, UUID holdId, UUID orderId) {
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                if (store.finishSale(eventId, holdId, orderId) instanceof RedisHoldStore.Finish.Sold sold) {
                    // Postgres sold these seats, so another Redis hold naming one of them loses (as R3 does).
                    sold.overwrittenHolds().forEach(other -> store.release(eventId, other, ReleaseReason.RELEASED));
                }
                return;
            } catch (RuntimeException e) {
                log.warn("Hold {}: sold in Postgres to order {}, but finishing the sale in Redis failed "
                        + "(attempt {}/2); reconciler rule R4 will finish it", holdId, orderId, attempt, e);
            }
        }
    }

    /** The event's load epoch, loading the event first if Redis doesn't have it. */
    private Optional<String> loadedEpoch(UUID eventId) {
        Optional<String> epoch = store.loadEpoch(eventId);
        if (epoch.isEmpty()) {
            loader.ensureLoaded(eventId);
            epoch = store.loadEpoch(eventId);
        }
        if (epoch.isEmpty()) {
            // Never compare against "not loaded": an absent hold would then look like a real GONE.
            throw new IllegalStateException("Event " + eventId + " is not loaded into Redis after loading it");
        }
        return epoch;
    }
}
