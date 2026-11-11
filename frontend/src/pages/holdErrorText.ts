import { ApiError } from '../api/problem'
import { joinNames } from '../seatmap/seatNames'
import { formatCountdown } from '../time/serverClock'

export interface HoldErrorContext {
  /** Names a seat id for the message ("Floor Left, row A, seat 3"). */
  seatName: (seatId: string) => string
  /** Server-clock milliseconds until the drop; recomputed on every render, so the text counts down. */
  msUntilDrop: number
}

/**
 * Plain-words text for errors from holding or releasing seats, chosen by the stable `code`.
 * HOLD_EXPIRED, HOLD_NOT_FOUND and HOLD_CONVERTED are mostly for checkout (M1.8c part 2), which
 * reuses this; on the seats page an expired hold is noticed by the countdown.
 */
export function holdErrorText(error: unknown, context: HoldErrorContext): string {
  if (!(error instanceof ApiError)) {
    return 'Something went wrong. Try again.'
  }
  switch (error.code) {
    case 'SEAT_TAKEN':
      return seatTakenText(error, context)
    case 'LIMIT_EXCEEDED':
      return error.limit === undefined
        ? 'That would take you over the ticket limit for this drop.'
        : `You can have at most ${error.limit} tickets for this drop, counting seats you already hold or bought.`
    case 'SALE_NOT_OPEN':
      return context.msUntilDrop > 0
        ? `Sales open in ${formatCountdown(context.msUntilDrop)}.`
        : "This drop isn't on sale right now."
    case 'HOLD_EXPIRED':
      return 'Your hold expired and the seats were released. Pick again.'
    case 'HOLD_NOT_FOUND':
      return 'That hold no longer exists. Pick your seats again.'
    case 'HOLD_CONVERTED':
      return 'These seats are already bought.'
    case 'EVENT_NOT_FOUND':
      return 'No such drop.'
    case 'VALIDATION_FAILED':
      // The only way the page can send a bad request is with seat ids from an out-of-date layout.
      return 'Some seats no longer exist. Reload the page.'
    case 'NETWORK_ERROR':
      // The hold may have been made before the connection dropped. Retrying with the same seats is
      // safe: the server returns the existing hold instead of a conflict (D-016).
      return "The server could not be reached. Try again. If your hold went through, you'll get the same hold back."
    case 'INTERNAL_ERROR':
      return error.errorId ? `Server error. Reference ${error.errorId}.` : 'Server error.'
    default:
      return 'Something went wrong. Try again.'
  }
}

function seatTakenText(error: ApiError, context: HoldErrorContext): string {
  const names = error.seatIds.map(context.seatName)
  const who = names.length === 0 ? 'A seat you picked' : joinNames(names)
  const verb = names.length > 1 ? 'were' : 'was'
  const when = error.takenAgoMs === undefined ? 'just now' : `${formatAgo(error.takenAgoMs)} ago`
  return `${who} ${verb} taken ${when}. Pick ${names.length > 1 ? 'others' : 'another'}.`
}

/** 40 → "40 ms", 3400 → "3 s", 125000 → "2 min" (PRD 8.4: "Taken 40 ms ago"). */
export function formatAgo(ms: number): string {
  if (ms < 1000) {
    return `${Math.max(0, Math.round(ms))} ms`
  }
  if (ms < 60_000) {
    return `${Math.round(ms / 1000)} s`
  }
  return `${Math.round(ms / 60_000)} min`
}
