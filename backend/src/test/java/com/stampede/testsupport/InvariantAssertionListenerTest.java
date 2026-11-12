package com.stampede.testsupport;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestContext;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Proves the after-test assertion (M1.7, D-019) fails a test that leaves an impossible state behind,
 * and that {@link ExpectInvariantViolations} must match exactly. The listener is called directly
 * with a {@link TestContext} that wraps this class's real Spring context and database, so what it
 * checks is real committed data; only "which test method just ran" is stubbed.
 * <p>
 * Each test repairs the database before it ends, so the listener's real run after it passes.
 */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class InvariantAssertionListenerTest {

    /** Its own database on the shared test Postgres (TestDatabases): nothing else ever sees its data. */
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabases.register(registry, "invariant_assertion_listener");
    }

    static final UUID USER = UUID.fromString("00000000-0000-4000-8000-0000000000a1");
    static final UUID VENUE = UUID.fromString("00000000-0000-4000-8000-000000000001");
    static final UUID EVENT = UUID.fromString("00000000-0000-4000-8000-00000000000e");

    @Autowired
    ApplicationContext context;

    @Autowired
    JdbcClient jdbc;

    final InvariantAssertionListener listener = new InvariantAssertionListener();

    /** Stand-ins for the test method that just ran. */
    static class Samples {
        void plain() {
        }

        @ExpectInvariantViolations(rules = {"I8"}, reason = "drift on purpose")
        void expectsI8() {
        }

        @ExpectInvariantViolations(rules = {"O7"}, reason = "the wrong rule")
        void expectsO7() {
        }

        @ExpectInvariantViolations(rules = {"I8"}, reason = " ")
        void blankReason() {
        }

        @ExpectInvariantViolations(rules = {"I8", "X9"}, reason = "a rule that doesn't exist")
        void unknownRule() {
        }
    }

    @BeforeEach
    void insertEvent() {
        jdbc.sql("TRUNCATE users, venues CASCADE").update();
        jdbc.sql("INSERT INTO users (id, email, password_hash) VALUES (?, 'buyer@example.test', 'x')").param(USER).update();
        jdbc.sql("INSERT INTO venues (id, name, layout_json) VALUES (?, 'Venue', '{}')").param(VENUE).update();
        jdbc.sql("""
                INSERT INTO events (id, venue_id, title, starts_at, drop_at, status, per_user_limit, strategy)
                VALUES (?, ?, 'Event', now() + interval '30 days', now() - interval '1 hour', 'LIVE', 4, 'PESSIMISTIC')
                """).params(EVENT, VENUE).update();
    }

    /** Leaves nothing broken for the listener's real run after each test here. */
    @AfterEach
    void repair() {
        jdbc.sql("DELETE FROM user_event_allowance").update();
    }

    @Test
    void aCleanDatabasePasses() {
        assertThatCode(() -> listener.afterTestExecution(after("plain"))).doesNotThrowAnyException();
    }

    @Test
    void aTestThatLeavesAnImpossibleStateFails() {
        leaveAllowanceDrift();

        assertThatThrownBy(() -> listener.afterTestExecution(after("plain")))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("Invariant check after " + Samples.class.getName() + "#plain")
                .hasMessageContaining("I8 allowance drift: user " + USER + " has used = 1 but 0 seats");
        repair();
    }

    @Test
    void anAnnotationNamingExactlyTheRulesThatFirePasses() {
        leaveAllowanceDrift();

        assertThatCode(() -> listener.afterTestExecution(after("expectsI8"))).doesNotThrowAnyException();
        repair();
    }

    @Test
    void anAnnotationNamingOtherRulesFailsAndStillShowsTheViolation() {
        leaveAllowanceDrift();

        assertThatThrownBy(() -> listener.afterTestExecution(after("expectsO7")))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("expected exactly [O7] to fire (the wrong rule) but [I8] fired")
                .hasMessageContaining("I8 allowance drift");
        repair();
    }

    @Test
    void anAnnotationWhoseRulesNoLongerFireFails() {
        // The bug it documented is gone: the annotation must go too.
        assertThatThrownBy(() -> listener.afterTestExecution(after("expectsI8")))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("expected exactly [I8] to fire (drift on purpose) but [] fired: none");
    }

    @Test
    void anAnnotationWithoutAReasonFailsEvenWhenItsRulesMatch() {
        leaveAllowanceDrift();

        assertThatThrownBy(() -> listener.afterTestExecution(after("blankReason")))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("@ExpectInvariantViolations needs a reason");
        repair();
    }

    @Test
    void anAnnotationNamingAnUnknownRuleFails() {
        leaveAllowanceDrift();

        assertThatThrownBy(() -> listener.afterTestExecution(after("unknownRule")))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("@ExpectInvariantViolations names unknown rules [X9]");
        repair();
    }

    /** Committed: a counter row for seats the user doesn't hold (I8). */
    private void leaveAllowanceDrift() {
        jdbc.sql("INSERT INTO user_event_allowance (event_id, user_id, used) VALUES (?, ?, 1)")
                .params(EVENT, USER).update();
    }

    private TestContext after(String sampleMethod) throws NoSuchMethodException {
        Method method = Samples.class.getDeclaredMethod(sampleMethod);
        TestContext testContext = mock(TestContext.class);
        when(testContext.getApplicationContext()).thenReturn(context);
        when(testContext.getTestMethod()).thenReturn(method);
        when(testContext.getTestClass()).thenAnswer(invocation -> Samples.class);
        return testContext;
    }
}
