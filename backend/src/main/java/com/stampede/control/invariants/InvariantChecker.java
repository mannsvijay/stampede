package com.stampede.control.invariants;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The invariant checker (PRD 6.10, M1.7, D-019): runs every {@link InvariantCheck} for an event and
 * returns the violations, so an empty list means every rule holds.
 * <p>
 * <b>One snapshot per event.</b> An event's rules run in one READ ONLY transaction at REPEATABLE READ:
 * every query sees the same committed moment, so two rules can't disagree because a checkout
 * committed between them, and the checker can't write. Called inside an existing transaction (a
 * {@code @Transactional} test), it joins that transaction instead and sees its uncommitted rows.
 * <p>
 * Used by the after-test assertion and the concurrency harness (strict, grace {@link Duration#ZERO})
 * and by the scheduled run (with a grace period for checkouts still running).
 */
@Component
public class InvariantChecker {

    private final JdbcClient jdbc;
    private final TransactionTemplate snapshot;
    private final List<InvariantCheck> checks;

    /** Every SQL rule. M2.2: add the Redis hold-consistency check (PRD 6.10 #5) to this list. */
    @Autowired
    public InvariantChecker(JdbcClient jdbc, PlatformTransactionManager transactions) {
        this(jdbc, transactions, SqlRules.ALL);
    }

    InvariantChecker(JdbcClient jdbc, PlatformTransactionManager transactions, List<InvariantCheck> checks) {
        this.jdbc = jdbc;
        this.checks = List.copyOf(checks);
        this.snapshot = new TransactionTemplate(transactions);
        snapshot.setReadOnly(true);
        snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    /** The ids of every rule this checker runs, in report order. */
    public List<String> ruleIds() {
        return checks.stream().map(InvariantCheck::id).toList();
    }

    /** Every violation for one event. {@code grace} ZERO = strict. */
    public List<Violation> check(UUID eventId, Duration grace) {
        if (grace.isNegative()) {
            throw new IllegalArgumentException("grace must not be negative: " + grace);
        }
        return snapshot.execute(status -> {
            List<Violation> violations = new ArrayList<>();
            for (InvariantCheck check : checks) {
                for (String detail : check.findViolations(jdbc, eventId, grace)) {
                    violations.add(new Violation(check.id(), check.name(), eventId, detail));
                }
            }
            return violations;
        });
    }

    /** Every violation for every event (one snapshot per event), in event id order. */
    public List<Violation> checkAllEvents(Duration grace) {
        return checkEach(jdbc.sql("SELECT id FROM events ORDER BY id").query(UUID.class).list(), grace);
    }

    /** Every violation for the events that are neither DRAFT nor ENDED (where buying can still happen). */
    public List<Violation> checkOpenEvents(Duration grace) {
        return checkEach(jdbc.sql("""
                        SELECT id FROM events WHERE status IN ('SCHEDULED', 'LIVE', 'PAUSED', 'SOLD_OUT') ORDER BY id
                        """).query(UUID.class).list(), grace);
    }

    private List<Violation> checkEach(List<UUID> eventIds, Duration grace) {
        List<Violation> violations = new ArrayList<>();
        for (UUID eventId : eventIds) {
            violations.addAll(check(eventId, grace));
        }
        return violations;
    }
}
