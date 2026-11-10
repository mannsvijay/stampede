/**
 * The payment provider behind an interface (M1.5, D-017): {@link PaymentProvider}, implemented for now
 * by {@link FakePaymentProvider}. The mock provider with chaos (PRD 6.6, M3) replaces it. Internal to
 * payments; other modules pay through {@code payments.api.PaymentService}.
 */
package com.stampede.payments.provider;
