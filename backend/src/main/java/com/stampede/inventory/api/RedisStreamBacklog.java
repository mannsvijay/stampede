package com.stampede.inventory.api;

import java.util.UUID;

/**
 * Whether the Redis-first inventory stream (D-022) still has entries of an event that Postgres hasn't
 * received yet. {@code control} uses it before an on-demand invariant check (M2.4a, D-024): only once
 * an event has none does invariant 5 (Redis consistent with Postgres) compare two stores that should
 * agree exactly.
 */
public interface RedisStreamBacklog {

    /**
     * True while the stream has an entry of this event that the consumer group hasn't acknowledged:
     * pending (delivered, not acknowledged) or not delivered yet. Exact: it reads every such entry in
     * pages, with no cap, so it is for occasional checks, not a hot path.
     */
    boolean hasUnappliedEntries(UUID eventId);
}
