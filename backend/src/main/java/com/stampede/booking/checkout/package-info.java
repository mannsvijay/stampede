/**
 * Checkout (M1.5, D-017): POST /orders turns the caller's hold into a PAID order synchronously, and
 * GET /orders/{id} shows it to its owner. {@link com.stampede.booking.checkout.CheckoutService} is the
 * orchestrator; each step is its own transaction, in the shape of the M3 saga (PRD 6.4). Internal to
 * booking.
 */
package com.stampede.booking.checkout;
