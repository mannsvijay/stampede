package com.stampede.inventory.api;

/** The result of {@link HoldService#cancel}. */
public sealed interface CancelResult {

    /** The hold was ACTIVE and is now RELEASED; its seats are AVAILABLE and back in the allowance. */
    record Cancelled() implements CancelResult {
    }

    /** The caller's hold had already ended (RELEASED or EXPIRED): nothing to do, nothing changed. */
    record AlreadyEnded(String holdStatus) implements CancelResult {
    }

    /** The caller's hold became an order; it can't be released. Nothing changed. */
    record Converted() implements CancelResult {
    }

    /** No hold with this id belongs to the caller (unknown, or someone else's). Nothing changed. */
    record NotFound() implements CancelResult {
    }
}
