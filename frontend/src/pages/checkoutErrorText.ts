import { ApiError } from '../api/problem'
import { type HoldErrorContext, holdErrorText } from './holdErrorText'

/**
 * Text for errors from POST /orders. Three codes mean something different at checkout than when
 * holding; everything else (HOLD_EXPIRED, HOLD_NOT_FOUND, INTERNAL_ERROR, ...) reads the same as
 * in {@link holdErrorText}, which this delegates to.
 */
export function checkoutErrorText(error: unknown, context: HoldErrorContext): string {
  if (error instanceof ApiError) {
    switch (error.code) {
      case 'SEAT_TAKEN':
        // Not lost: another checkout that hasn't finished still lists a seat of this hold (D-017).
        return 'Another checkout is still finishing with one of these seats. Try again in a few seconds.'
      case 'PAYMENT_FAILED':
        return 'Payment declined. Nothing was charged, and your seats were released. Pick again.'
      case 'NETWORK_ERROR':
        // One hold has at most one order, so a retry returns the order if the first try went through.
        return "The server could not be reached. Try again: you won't be charged twice for these seats."
    }
  }
  return holdErrorText(error, context)
}

/** Errors where the same hold is still good and paying again is the right next step. */
export function isRetryableCheckoutError(error: unknown): boolean {
  return error instanceof ApiError && (error.code === 'SEAT_TAKEN' || error.code === 'NETWORK_ERROR')
}
