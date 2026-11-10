package com.stampede.testsupport;

import com.stampede.control.invariants.InvariantChecker;
import com.stampede.control.invariants.Violation;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.support.AbstractTestExecutionListener;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The hard assertion after every integration test (PRD 6.10, M1.7, D-019): when a test's body has
 * finished, every invariant must hold for every event in its database, strictly (no grace period:
 * the requests under test have returned). Otherwise the test fails with the violations.
 * <p>
 * <b>Registered for every Spring test</b> through {@code META-INF/spring.factories}, so a new test
 * class can't forget it. Plain unit tests have no Spring context and never reach it; a context
 * without a DataSource is skipped.
 * <p>
 * <b>What it sees:</b> the committed database, in its own read-only REPEATABLE READ transaction:
 * what the test leaves behind.
 * <p>
 * <b>When:</b>
 * <ul>
 *   <li>Without a Spring test transaction (most tests): in {@code afterTestExecution}, right after the
 *       test method and before its {@code @AfterEach} methods, which is where tests delete their data.</li>
 *   <li>Inside a test transaction ({@code @Transactional} tests, the {@code @JdbcTest} default): in
 *       {@code afterTestMethod}, after Spring has rolled it back. Checking earlier would wait forever
 *       on the locks the open test transaction holds (a TRUNCATE in it locks whole tables) while the
 *       test thread waits for this check; and joining that transaction fails when the test aborted it
 *       on purpose (SchemaMigrationTest). Spring runs "after" callbacks in reverse order, so
 *       {@link #getOrder()} below TransactionalTestExecutionListener's 4000 puts this one after the
 *       rollback.</li>
 * </ul>
 * A 10 s lock timeout turns any other wait into a failure instead of a hang.
 * <p>
 * <b>Opting out:</b> {@link ExpectInvariantViolations} on the method, with the exact rules and a reason.
 * <p>
 * {@code -Dstampede.invariants.after-test=report} records instead of failing, to
 * {@code build/reports/invariants/after-test.txt}.
 */
public class InvariantAssertionListener extends AbstractTestExecutionListener {

    static final String MODE_PROPERTY = "stampede.invariants.after-test";
    static final Path REPORT = Path.of("build", "reports", "invariants", "after-test.txt");
    private static final String DEFERRED = InvariantAssertionListener.class.getName() + ".deferred";

    /** Before TransactionalTestExecutionListener (4000), so its after-method hook runs after the rollback. */
    @Override
    public int getOrder() {
        return 3_000;
    }

    @Override
    public void afterTestExecution(TestContext testContext) {
        if (TestTransaction.isActive()) {
            testContext.setAttribute(DEFERRED, Boolean.TRUE);
            return;
        }
        verify(testContext);
    }

    @Override
    public void afterTestMethod(TestContext testContext) {
        if (testContext.removeAttribute(DEFERRED) != null) {
            verify(testContext);
        }
    }

    private void verify(TestContext testContext) {
        ApplicationContext context = testContext.getApplicationContext();
        if (context.getBeanNamesForType(DataSource.class).length == 0) {
            return;
        }
        InvariantChecker checker = checker(context);
        TransactionTemplate committedSnapshot = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        committedSnapshot.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        committedSnapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        committedSnapshot.setReadOnly(true);
        JdbcClient jdbc = jdbc(context);
        List<Violation> violations = committedSnapshot.execute(status -> {
            jdbc.sql("SET LOCAL lock_timeout = '10s'").update();
            return checker.checkAllEvents(Duration.ZERO);
        });

        Method test = testContext.getTestMethod();
        ExpectInvariantViolations expected = test.getAnnotation(ExpectInvariantViolations.class);
        String problem = problem(violations, expected, Set.copyOf(checker.ruleIds()));
        if (problem == null) {
            return;
        }
        String name = testContext.getTestClass().getName() + "#" + test.getName();
        if ("report".equals(System.getProperty(MODE_PROPERTY))) {
            record(name, problem);
        } else {
            throw new AssertionError("Invariant check after " + name + ": " + problem);
        }
    }

    /**
     * Null when the test is fine; otherwise what is wrong. Exactly the annotated rules must fire, or
     * none at all without the annotation.
     */
    static String problem(List<Violation> violations, ExpectInvariantViolations expected, Set<String> knownRules) {
        Set<String> fired = violations.stream().map(Violation::ruleId).collect(Collectors.toCollection(TreeSet::new));
        String listing = violations.stream().map(v -> "\n  " + v).collect(Collectors.joining());
        if (expected == null) {
            return fired.isEmpty() ? null
                    : "the test left " + violations.size() + " violation(s) of " + fired + ":" + listing;
        }
        Set<String> declared = new TreeSet<>(Arrays.asList(expected.rules()));
        List<String> wrong = new ArrayList<>();
        if (expected.reason().isBlank()) {
            wrong.add("@ExpectInvariantViolations needs a reason");
        }
        if (declared.isEmpty()) {
            wrong.add("@ExpectInvariantViolations needs at least one rule");
        }
        Set<String> unknown = new TreeSet<>(declared);
        unknown.removeAll(knownRules);
        if (!unknown.isEmpty()) {
            wrong.add("@ExpectInvariantViolations names unknown rules " + unknown);
        }
        if (wrong.isEmpty() && fired.equals(declared)) {
            return null;
        }
        if (!fired.equals(declared)) {
            wrong.add("expected exactly " + declared + " to fire (" + expected.reason() + ") but " + fired + " fired:"
                    + (listing.isEmpty() ? " none" : listing));
        }
        return String.join("; ", wrong);
    }

    /** The app's checker when the context has one; a @JdbcTest slice builds its own. */
    private static InvariantChecker checker(ApplicationContext context) {
        return context.getBeanProvider(InvariantChecker.class).getIfAvailable(
                () -> new InvariantChecker(jdbc(context), context.getBean(PlatformTransactionManager.class)));
    }

    private static JdbcClient jdbc(ApplicationContext context) {
        return context.getBeanProvider(JdbcClient.class)
                .getIfAvailable(() -> JdbcClient.create(context.getBean(DataSource.class)));
    }

    private static synchronized void record(String test, String problem) {
        try {
            Files.createDirectories(REPORT.getParent());
            Files.writeString(REPORT, test + "\n  " + problem + "\n\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
