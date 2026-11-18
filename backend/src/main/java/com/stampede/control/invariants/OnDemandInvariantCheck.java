package com.stampede.control.invariants;

import com.stampede.inventory.api.RedisStreamBacklog;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Runs the invariant checker for one event when asked (M2.4a, D-024): {@code GET /admin/invariants},
 * which k6.sh calls after a load run for its verdict.
 * <p>
 * <b>Order matters.</b> For a REDIS_FIRST event it first asks whether the inventory stream still has
 * entries of the event that Postgres hasn't received, and only then runs the checker. So when
 * {@link Result#unappliedStreamEntries()} is false, every hold Redis had written before the check
 * started was already in Postgres, and invariant 5 (I10) compares two stores that should agree exactly.
 * When it is true, the violations may only be that lag; ask again.
 */
@Component
public class OnDemandInvariantCheck {

    private final JdbcClient jdbc;
    private final InvariantChecker checker;
    private final ObjectProvider<RedisStreamBacklog> backlog;

    OnDemandInvariantCheck(JdbcClient jdbc, InvariantChecker checker, ObjectProvider<RedisStreamBacklog> backlog) {
        this.jdbc = jdbc;
        this.checker = checker;
        this.backlog = backlog;
    }

    /**
     * @param strategy               the event's inventory strategy when checked
     * @param unappliedStreamEntries read before the check; always false for an event that isn't
     *                               REDIS_FIRST (switching away is refused while entries are unapplied, D-023)
     * @param grace                  ZERO = strict
     * @param rules                  every rule that ran, in report order
     */
    public record Result(UUID eventId, String strategy, boolean unappliedStreamEntries, Duration grace,
                         Instant checkedAt, List<String> rules, List<Violation> violations) {

        public Result {
            rules = List.copyOf(rules);
            violations = List.copyOf(violations);
        }
    }

    /** Empty if there is no event with this id (any status, DRAFT included). */
    public Optional<Result> check(UUID eventId, Duration grace) {
        Optional<String> strategy = jdbc.sql("SELECT strategy FROM events WHERE id = ?")
                .param(eventId).query(String.class).optional();
        if (strategy.isEmpty()) {
            return Optional.empty();
        }
        boolean unapplied = "REDIS_FIRST".equals(strategy.get()) && hasUnappliedEntries(eventId);
        Instant checkedAt = Instant.now();
        List<Violation> violations = checker.check(eventId, grace);
        return Optional.of(new Result(eventId, strategy.get(), unapplied, grace, checkedAt, checker.ruleIds(),
                violations));
    }

    /**
     * A context without the Redis beans can't tell, so it fails (500) rather than claim nothing is
     * unapplied. The application always has them.
     */
    private boolean hasUnappliedEntries(UUID eventId) {
        RedisStreamBacklog stream = backlog.getIfAvailable();
        if (stream == null) {
            throw new IllegalStateException("Event " + eventId + " is REDIS_FIRST, but this application has no "
                    + "Redis inventory stream to ask");
        }
        return stream.hasUnappliedEntries(eventId);
    }
}
