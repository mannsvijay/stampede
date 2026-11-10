package com.stampede.payments.api;

import java.util.UUID;

/** The result of {@link PaymentService#charge}. */
public sealed interface ChargeResult {

    /** The money is taken; the payment row is SUCCEEDED. */
    record Succeeded(UUID paymentId) implements ChargeResult {
    }

    /** The provider declined; nothing was taken and the payment row is FAILED. */
    record Declined(UUID paymentId, String reason) implements ChargeResult {
    }
}
