package com.stampede.payments.provider;

import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Always succeeds, instantly (D-017, option 5B). No switch to make it fail: tests that need a decline
 * or an outage replace this bean (for example with {@code @MockitoSpyBean}), and the M3 chaos provider
 * replaces it for real.
 */
@Component
class FakePaymentProvider implements PaymentProvider {

    @Override
    public Charge charge(UUID paymentId, long amountPaise) {
        return Charge.success("fake_" + paymentId);
    }

    @Override
    public void refund(String providerRef) {
        // Nothing to give back: no money moved.
    }
}
