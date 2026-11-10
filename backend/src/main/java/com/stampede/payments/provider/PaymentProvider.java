package com.stampede.payments.provider;

import java.util.UUID;

/**
 * A payment gateway, as {@code PaymentService} sees it. Synchronous for now (M1.5); the M3 mock
 * provider adds intents, client confirmation and webhooks (PRD 6.6) behind its own adapter.
 * <p>
 * A declined charge is a normal answer ({@link Charge#succeeded()} false). An exception means the
 * outcome is unknown (timeout, outage), and the caller has to treat the money as possibly taken.
 */
public interface PaymentProvider {

    /** The provider's answer to a charge. {@code providerRef} identifies it for a later refund. */
    record Charge(boolean succeeded, String providerRef, String declineReason) {
        public static Charge success(String providerRef) {
            return new Charge(true, providerRef, null);
        }

        public static Charge declined(String providerRef, String reason) {
            return new Charge(false, providerRef, reason);
        }
    }

    /**
     * Charges {@code amountPaise}. {@code paymentId} is our id for this attempt; a real provider uses
     * it as its idempotency key, so a retried call can't charge twice.
     */
    Charge charge(UUID paymentId, long amountPaise);

    /** Refunds a successful charge in full. */
    void refund(String providerRef);
}
