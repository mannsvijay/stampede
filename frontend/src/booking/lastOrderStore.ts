import { PREFIX } from './holdStore'

/**
 * The order this tab placed last, kept in sessionStorage (there is no GET /me/orders). The wallet
 * compares it with the tickets it got: an order can be PAID before its tickets are issued (D-018),
 * and then the wallet offers to retry POST /orders for the same hold, which issues them.
 *
 * The key starts with `stampede:`, so clearBookingState() removes it on logout and on a new login.
 */
export interface LastOrder {
  orderId: string
  holdId: string
  eventId: string
  /** How many seats the order has: the number of tickets it should get. */
  itemCount: number
}

const KEY = `${PREFIX}last-order`

export function saveLastOrder(order: LastOrder): void {
  try {
    sessionStorage.setItem(KEY, JSON.stringify(order))
  } catch {
    // Storage full or disabled: the wallet then can't offer a retry, but the order is still placed.
  }
}

/** The stored last order, or null if there is none or it is malformed (then it is removed). */
export function loadLastOrder(): LastOrder | null {
  let order: unknown
  try {
    const raw = sessionStorage.getItem(KEY)
    order = raw === null ? null : JSON.parse(raw)
  } catch {
    order = null
  }
  if (!isLastOrder(order)) {
    clearLastOrder()
    return null
  }
  return order
}

export function clearLastOrder(): void {
  try {
    sessionStorage.removeItem(KEY)
  } catch {
    // Nothing stored, then.
  }
}

function isLastOrder(value: unknown): value is LastOrder {
  if (typeof value !== 'object' || value === null) {
    return false
  }
  const v = value as Record<string, unknown>
  return (
    typeof v.orderId === 'string' &&
    typeof v.holdId === 'string' &&
    typeof v.eventId === 'string' &&
    typeof v.itemCount === 'number' &&
    Number.isInteger(v.itemCount) &&
    v.itemCount > 0
  )
}
