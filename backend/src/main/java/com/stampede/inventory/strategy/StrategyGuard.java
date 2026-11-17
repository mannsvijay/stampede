package com.stampede.inventory.strategy;

import com.stampede.inventory.strategy.HoldClosing.CloseDecision;
import com.stampede.inventory.strategy.HoldClosing.CloseGate;
import com.stampede.inventory.strategy.HoldClosing.SellGate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The in-transaction re-check of an event's strategy and sale window (M2.3a, D-023).
 * <p>
 * {@code HoldService} reads the event's strategy and sale window without a lock, before it calls a
 * strategy. Either can change right after that read: an admin switches the strategy, or the sale
 * closes. So every strategy asks again, <b>inside its own transaction, after it has taken its first
 * row lock</b>, and answers {@code StrategyChanged} (writing nothing) if the event is no longer its
 * own. Why "after a lock" matters: the strategy switch locks every seat of the event before it changes
 * {@code events.strategy}. A hold that already holds one of those seat locks makes the switch wait
 * for it, and the switch then sees its hold and refuses; a hold that comes later waits for the
 * switch's locks, and this read, a new statement under READ COMMITTED, sees the switch's commit.
 * <p>
 * The reads take no lock of their own (one primary-key lookup), so the lock order of D-015 is
 * unchanged. Redis-first holds have no Postgres transaction; for them the gate is the event's
 * {@code closed} key in Redis instead (checked by {@code hold.lua} and {@code load.lua}).
 */
@Component
class StrategyGuard {

    private final JdbcClient jdbc;

    StrategyGuard(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Whether the event still uses this strategy. False for an unknown event. */
    boolean serves(UUID eventId, StrategyKind kind) {
        return jdbc.sql("SELECT strategy FROM events WHERE id = :eventId")
                .param("eventId", eventId)
                .query(String.class)
                .optional()
                .map(kind.name()::equals)
                .orElse(false);
    }

    /**
     * Whether the event still takes holds through this strategy: its strategy, and on sale (after the
     * drop, while SCHEDULED or LIVE: the same rule as {@code HoldService}'s pre-check, D-016). The
     * database clock decides, as for every hold time (D-015).
     */
    boolean takesHolds(UUID eventId, StrategyKind kind) {
        return jdbc.sql("""
                        SELECT strategy = :kind AND status IN ('SCHEDULED', 'LIVE') AND drop_at <= now()
                        FROM events WHERE id = :eventId
                        """)
                .param("kind", kind.name())
                .param("eventId", eventId)
                .query(Boolean.class)
                .optional()
                .orElse(false);
    }

    /**
     * A release gate that first checks the strategy, then decides with {@code inner}. Runs once the hold
     * row is locked and ACTIVE, before anything is written (and, for Redis-first, before any Lua runs).
     */
    CloseGate release(StrategyKind kind, CloseGate inner) {
        return hold -> serves(hold.eventId(), kind)
                ? inner.decide(hold)
                : new CloseDecision.Keep(new ReleaseOutcome.StrategyChanged());
    }

    /** {@link #release}, for a sale. */
    SellGate sell(StrategyKind kind, SellGate inner) {
        return hold -> serves(hold.eventId(), kind)
                ? inner.check(hold)
                : Optional.of(new SellOutcome.StrategyChanged());
    }
}
