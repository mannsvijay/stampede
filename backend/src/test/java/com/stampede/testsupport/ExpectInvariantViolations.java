package com.stampede.testsupport;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a test that leaves an impossible state on purpose (M1.7, D-019): a documented failure
 * outcome, or a deliberately broken row that the code under test must refuse to touch.
 * <p>
 * This does not switch the after-test check off. {@link InvariantAssertionListener} still runs every
 * rule and fails the test unless the rules that fire are <em>exactly</em> {@link #rules()}: an
 * unrelated break is still caught, and an annotation that no longer matches (the bug it documents
 * is gone) fails too. A blank {@link #reason()} fails the test.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ExpectInvariantViolations {

    /** The ids of the rules this test leaves broken, e.g. {"O1", "O5", "O7"}. */
    String[] rules();

    /** Why the state is expected, with the decision that documents it (e.g. "D-017: ..."). */
    String reason();
}
