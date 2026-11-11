/**
 * The current hold, kept in sessionStorage (no GET /me/holds exists): a reload of the seats page
 * brings back the countdown and the Release button. sessionStorage belongs to one tab and is gone
 * when the tab closes.
 *
 * Every key this app stores starts with `stampede:`, so {@link clearBookingState} can remove them all
 * on logout and on a new login (another person may be using this computer).
 */

export interface StoredHold {
  holdId: string
  eventId: string
  seatIds: string[]
  /** Server time (ISO 8601). */
  expiresAt: string
  /** Server clock minus laptop clock, measured when the hold was made (see time/serverClock.ts). */
  clockOffsetMs: number
}

/** Every sessionStorage key of this app starts with this (see lastOrderStore.ts too). */
export const PREFIX = 'stampede:'
const holdKey = (eventId: string) => `${PREFIX}hold:${eventId}`

export function saveHold(hold: StoredHold): void {
  try {
    sessionStorage.setItem(holdKey(hold.eventId), JSON.stringify(hold))
  } catch {
    // Storage full or disabled (some private modes): the hold still works until a reload.
  }
}

/**
 * The stored hold for this event, or null if there is none, it is malformed, or it has already
 * expired by the server's clock (then it is removed).
 */
export function loadHold(eventId: string, laptopNow: number = Date.now()): StoredHold | null {
  let hold: unknown
  try {
    const raw = sessionStorage.getItem(holdKey(eventId))
    hold = raw === null ? null : JSON.parse(raw)
  } catch {
    hold = null
  }
  if (!isStoredHold(hold) || hold.eventId !== eventId) {
    clearHold(eventId)
    return null
  }
  if (Date.parse(hold.expiresAt) <= laptopNow + hold.clockOffsetMs) {
    clearHold(eventId)
    return null
  }
  return hold
}

export function clearHold(eventId: string): void {
  try {
    sessionStorage.removeItem(holdKey(eventId))
  } catch {
    // Nothing stored, then.
  }
}

/** Removes everything this app keeps in sessionStorage: every hold and the last order. */
export function clearBookingState(): void {
  try {
    const keys: string[] = []
    for (let i = 0; i < sessionStorage.length; i++) {
      const key = sessionStorage.key(i)
      if (key?.startsWith(PREFIX)) {
        keys.push(key) // collect first: removing while iterating shifts the indexes
      }
    }
    keys.forEach((key) => sessionStorage.removeItem(key))
  } catch {
    // Storage disabled: there is nothing to clear.
  }
}

function isStoredHold(value: unknown): value is StoredHold {
  if (typeof value !== 'object' || value === null) {
    return false
  }
  const v = value as Record<string, unknown>
  return (
    typeof v.holdId === 'string' &&
    typeof v.eventId === 'string' &&
    Array.isArray(v.seatIds) &&
    v.seatIds.every((id) => typeof id === 'string') &&
    typeof v.expiresAt === 'string' &&
    !Number.isNaN(Date.parse(v.expiresAt)) &&
    typeof v.clockOffsetMs === 'number' &&
    Number.isFinite(v.clockOffsetMs)
  )
}
