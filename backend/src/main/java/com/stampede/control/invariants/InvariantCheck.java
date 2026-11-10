package com.stampede.control.invariants;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * One invariant rule (M1.7, D-019). It returns a description of every row that breaks the rule for
 * one event, so an empty list means the rule holds.
 * <p>
 * Today every rule is a {@link SqlCheck}. M2.2 adds the Redis rule (PRD 6.10 invariant 5, "Redis hold
 * state is consistent with Postgres") as another implementation of this interface and adds it to
 * the list in {@link InvariantChecker}; the checker itself does not change.
 */
public interface InvariantCheck {

    /** Short stable id, e.g. "I1" or "T3"; opt-out annotations in tests name rules by it. */
    String id();

    /** What the rule says, as read in a report. */
    String name();

    /**
     * @param jdbc    runs inside the checker's read-only snapshot transaction for this event
     * @param grace   {@link Duration#ZERO} = strict (tests): a briefly legitimate in-between state is
     *                reported too. Otherwise such a state is excused while its order is younger
     *                than this (a checkout still running).
     */
    List<String> findViolations(JdbcClient jdbc, UUID eventId, Duration grace);
}
