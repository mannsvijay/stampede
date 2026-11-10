package com.stampede.control.invariants;

import java.util.UUID;

/** One row that breaks one rule. {@link #toString()} is the line tests and logs print. */
public record Violation(String ruleId, String ruleName, UUID eventId, String detail) {

    /** "T3 PAID order whose tickets aren't issued: order ..." (tests match on this prefix). */
    @Override
    public String toString() {
        return ruleId + " " + ruleName + ": " + detail;
    }
}
