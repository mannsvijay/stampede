package com.stampede.inventory.api;

/** The result of {@link StrategySwitch#switchStrategy}. */
public sealed interface SwitchResult {

    /** {@code events.strategy} is now {@code to}, and the audit row is written. */
    record Switched(EventStrategy from, EventStrategy to) implements SwitchResult {
    }

    /** The event already uses this strategy: nothing changed, no audit row. */
    record Unchanged(EventStrategy strategy) implements SwitchResult {
    }

    /** Nothing changed; {@code strategy} is the event's strategy, which it keeps. */
    record Refused(Reason reason, EventStrategy strategy) implements SwitchResult {
    }

    /** No event with this id. */
    record EventNotFound() implements SwitchResult {
    }

    /** Why a switch was refused (the {@code reason} of a 409 STRATEGY_SWITCH_REFUSED). */
    enum Reason {
        /** The event takes holds now: after its drop, while SCHEDULED or LIVE. */
        ON_SALE,
        /** The event is over; its strategy no longer matters. */
        EVENT_ENDED,
        /** The event has ACTIVE holds in Postgres (wait until they end and the sweeper has closed them). */
        LIVE_HOLDS,
        /** Switching away from REDIS_FIRST: Redis still has holds of the event (live, or pinned by a sale). */
        LIVE_REDIS_HOLDS,
        /** Switching away from REDIS_FIRST: the stream still has entries of the event the consumer hasn't applied. */
        UNAPPLIED_STREAM_ENTRIES,
        /** Another switch of this event is running. */
        SWITCH_IN_PROGRESS
    }
}
