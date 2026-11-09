package com.stampede.inventory.strategy;

/** The inventory strategies, with the same names as {@code ck_events_strategy} in V1. */
public enum StrategyKind {
    PESSIMISTIC,
    OPTIMISTIC,
    REDIS_FIRST
}
