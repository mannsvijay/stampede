package com.stampede.inventory.api;

/** The result of {@link HoldService#releaseForFailedCheckout}, decided under the hold row lock. */
public enum CheckoutRelease {
    /** The hold was ACTIVE; its seats are AVAILABLE again and back in the user's allowance. */
    RELEASED,
    /** The hold had already been released or had expired; nothing changed. */
    ALREADY_ENDED,
    /**
     * The hold is CONVERTED: the sale committed. Nothing changed, and the checkout must finish the
     * order instead of undoing it.
     */
    CONVERTED
}
