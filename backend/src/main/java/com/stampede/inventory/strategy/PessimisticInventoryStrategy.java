package com.stampede.inventory.strategy;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Strategy A (PRD 6.2): {@code SELECT ... FOR UPDATE} on the requested event_seats rows, locked in
 * Postgres {@code seat_id} order, inside one transaction (D-015).
 * <p>
 * M1.2: written by hand (PLAN.md). This skeleton only fixes the shape.
 */
@Component
class PessimisticInventoryStrategy implements InventoryStrategy {

    private final JdbcClient jdbc;

    PessimisticInventoryStrategy(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public StrategyKind kind() {
        return StrategyKind.PESSIMISTIC;
    }

    @Override
    public HoldOutcome hold(HoldRequest request) {
        throw new UnsupportedOperationException("M1.2: hold is not written yet");
    }

    @Override
    public ReleaseOutcome release(UUID eventId, UUID holdId, ReleaseReason reason) {
        throw new UnsupportedOperationException("M1.2: release is not written yet");
    }
}
