package com.stampede.inventory.api;

import java.util.UUID;

/**
 * Switches an event's inventory strategy (PRD OP2, PRD 10 {@code POST /admin/events/{id}/strategy},
 * M2.3a, D-023). A benchmarking feature, used between runs on an event that is not on sale.
 * <p>
 * A switch is allowed only when the event takes no holds (before its drop, or DRAFT, PAUSED or
 * SOLD_OUT; never ENDED) and nothing is live: no ACTIVE hold in Postgres and, when switching away from
 * REDIS_FIRST, no hold in Redis and no stream entry of the event left to apply. Anything else is
 * {@link SwitchResult.Refused} with the reason. One switch per event at a time.
 */
public interface StrategySwitch {

    /**
     * Switches the event to {@code target}, or explains why not.
     *
     * @param audit called once, inside the transaction that changes {@code events.strategy} and only
     *              when it does, so the audit row commits or rolls back with the switch
     */
    SwitchResult switchStrategy(UUID eventId, EventStrategy target, AuditWriter audit);

    /**
     * Records a switch in the audit log. Implemented by the caller (control owns {@code audit_log},
     * D-007), run by inventory inside its transaction: a failure here rolls the switch back.
     */
    @FunctionalInterface
    interface AuditWriter {
        void strategyChanged(UUID eventId, EventStrategy from, EventStrategy to);
    }
}
