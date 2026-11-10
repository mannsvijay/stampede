/**
 * Holds over HTTP (POST /events/{id}/holds, DELETE /holds/{id}) and the hold expiry sweeper (D-016).
 * Internal to inventory; other modules hold seats through {@code inventory.api.HoldService}.
 */
package com.stampede.inventory.holds;
