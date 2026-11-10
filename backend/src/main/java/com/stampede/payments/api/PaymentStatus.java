package com.stampede.payments.api;

import java.util.Set;

/**
 * The payment state machine ({@code ck_payments_status}). Every status change in
 * {@link PaymentService} asks {@link #canMoveTo} first, and its UPDATE repeats the expected current
 * status in the WHERE clause, so a concurrent change makes it update zero rows instead of overwriting.
 */
public enum PaymentStatus {
    CREATED, SUCCEEDED, FAILED, REFUNDED;

    /** CREATED to SUCCEEDED or FAILED; SUCCEEDED to REFUNDED. FAILED and REFUNDED are final. */
    public boolean canMoveTo(PaymentStatus next) {
        return switch (this) {
            case CREATED -> Set.of(SUCCEEDED, FAILED).contains(next);
            case SUCCEEDED -> next == REFUNDED;
            case FAILED, REFUNDED -> false;
        };
    }
}
