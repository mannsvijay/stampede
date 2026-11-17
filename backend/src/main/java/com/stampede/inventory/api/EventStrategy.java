package com.stampede.inventory.api;

/**
 * The inventory strategies an event can use (PRD 6.2), as other modules see them. The same names as
 * {@code events.strategy} and inventory's internal {@code StrategyKind} (a test keeps the two equal).
 */
public enum EventStrategy {
    PESSIMISTIC,
    OPTIMISTIC,
    REDIS_FIRST
}
