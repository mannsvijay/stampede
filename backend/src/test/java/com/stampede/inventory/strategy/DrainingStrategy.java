package com.stampede.inventory.strategy;

import java.util.UUID;

/**
 * Test-only: wraps the Redis-first strategy so the stream is drained into Postgres after every call,
 * whether it returned or threw. The behaviour contract ({@link InventoryStrategyContractTest}) reads
 * its results from Postgres; for Redis-first those arrive through the consumer, which no thread runs in
 * tests. Lag itself is tested separately ({@link RedisFirstInventoryStrategyTest} uses the bare
 * strategy for that).
 */
final class DrainingStrategy implements InventoryStrategy {

    private final InventoryStrategy inner;
    private final Runnable drain;

    /** @param drain must be safe to call from several threads (the race tests call it concurrently) */
    DrainingStrategy(InventoryStrategy inner, Runnable drain) {
        this.inner = inner;
        this.drain = drain;
    }

    @Override
    public StrategyKind kind() {
        return inner.kind();
    }

    @Override
    public HoldOutcome hold(HoldRequest request) {
        try {
            return inner.hold(request);
        } finally {
            drain.run();
        }
    }

    @Override
    public ReleaseOutcome release(UUID eventId, UUID holdId, ReleaseReason reason) {
        try {
            return inner.release(eventId, holdId, reason);
        } finally {
            drain.run();
        }
    }

    @Override
    public SellOutcome sell(UUID eventId, UUID holdId, UUID orderId) {
        try {
            return inner.sell(eventId, holdId, orderId);
        } finally {
            drain.run();
        }
    }
}
