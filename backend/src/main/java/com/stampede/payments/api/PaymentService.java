package com.stampede.payments.api;

import com.stampede.payments.provider.PaymentProvider;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Charges and refunds for orders (M1.5, D-017). Gets the order id and amount as arguments, so payments
 * depends on nothing (D-007). Owns the {@code payments} table.
 * <p>
 * Not {@code @Transactional}: each statement here commits on its own. The payment row is written
 * <b>before</b> the provider is called, so a crash during the call still leaves a record that money
 * may have moved (CREATED), which M3's reconciliation can check with the provider. Holding a database
 * transaction open across a network call would also keep a connection busy for its whole duration.
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final JdbcClient jdbc;
    private final PaymentProvider provider;

    PaymentService(JdbcClient jdbc, PaymentProvider provider) {
        this.jdbc = jdbc;
        this.provider = provider;
    }

    /**
     * Charges the order's total. A decline is a result; an exception from the provider propagates and
     * leaves the payment CREATED (outcome unknown), for {@link #refundOrder} or M3 to resolve.
     */
    public ChargeResult charge(UUID orderId, long amountPaise) {
        UUID paymentId = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO payments (id, order_id, status, amount_paise)
                        VALUES (:id, :orderId, 'CREATED', :amount)
                        """)
                .param("id", paymentId)
                .param("orderId", orderId)
                .param("amount", amountPaise)
                .update();

        PaymentProvider.Charge charge = provider.charge(paymentId, amountPaise);

        PaymentStatus outcome = charge.succeeded() ? PaymentStatus.SUCCEEDED : PaymentStatus.FAILED;
        move(paymentId, PaymentStatus.CREATED, outcome, charge.providerRef());
        return charge.succeeded()
                ? new ChargeResult.Succeeded(paymentId)
                : new ChargeResult.Declined(paymentId, charge.declineReason());
    }

    /**
     * Checkout compensation: undoes every payment of the order. SUCCEEDED is refunded through the
     * provider. CREATED means a charge whose outcome was never recorded: the provider call threw, or it
     * succeeded and recording that failed (then money DID move and we may not even have its
     * providerRef). Its outcome is unknown, so it is left CREATED and logged for reconciliation (M3
     * asks the provider by payment id, its idempotency key); closing it as FAILED here would lose a
     * real charge. FAILED and REFUNDED need nothing.
     *
     * @return how many payments were refunded
     */
    public int refundOrder(UUID orderId) {
        List<PaymentRow> payments = jdbc.sql("SELECT id, status, provider_ref FROM payments WHERE order_id = ?")
                .param(orderId)
                .query((rs, n) -> new PaymentRow(rs.getObject("id", UUID.class),
                        PaymentStatus.valueOf(rs.getString("status")), rs.getString("provider_ref")))
                .list();
        int refunded = 0;
        for (PaymentRow payment : payments) {
            switch (payment.status()) {
                case SUCCEEDED -> {
                    provider.refund(payment.providerRef());
                    move(payment.id(), PaymentStatus.SUCCEEDED, PaymentStatus.REFUNDED, payment.providerRef());
                    refunded++;
                }
                case CREATED -> log.error("Payment {} of order {} has no recorded outcome; left CREATED "
                        + "for reconciliation with the provider (M3)", payment.id(), orderId);
                case FAILED, REFUNDED -> {
                    // Nothing was taken, or it was already given back.
                }
            }
        }
        return refunded;
    }

    /**
     * One guarded status change: the state machine must allow it, and the row must still be in
     * {@code from} (the WHERE clause), or nothing is written and this throws.
     */
    private void move(UUID paymentId, PaymentStatus from, PaymentStatus to, String providerRef) {
        if (!from.canMoveTo(to)) {
            throw new IllegalStateException("Payment " + paymentId + " cannot move from " + from + " to " + to);
        }
        int updated = jdbc.sql("""
                        UPDATE payments SET status = :to, provider_ref = coalesce(CAST(:ref AS text), provider_ref)
                        WHERE id = :id AND status = :from
                        """)
                .param("to", to.name())
                .param("ref", providerRef)
                .param("id", paymentId)
                .param("from", from.name())
                .update();
        if (updated != 1) {
            throw new IllegalStateException("Payment " + paymentId + " was not " + from + " when moving to " + to);
        }
    }

    private record PaymentRow(UUID id, PaymentStatus status, String providerRef) {
    }
}
