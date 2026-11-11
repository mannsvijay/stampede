import { afterEach, describe, expect, it } from 'vitest'

import { clearBookingState, saveHold } from './holdStore'
import { clearLastOrder, type LastOrder, loadLastOrder, saveLastOrder } from './lastOrderStore'

const ORDER: LastOrder = { orderId: 'order-1', holdId: 'hold-1', eventId: 'event-1', itemCount: 2 }

describe('lastOrderStore', () => {
  afterEach(() => sessionStorage.clear())

  it('saves and loads the last order under a stampede: key; a newer order replaces it', () => {
    saveLastOrder(ORDER)
    expect(loadLastOrder()).toEqual(ORDER)
    expect(Object.keys(sessionStorage)).toEqual(['stampede:last-order'])

    saveLastOrder({ ...ORDER, orderId: 'order-2' })
    expect(loadLastOrder()?.orderId).toBe('order-2')
  })

  it('ignores and removes malformed data', () => {
    sessionStorage.setItem('stampede:last-order', '{"orderId": "order-1", "itemCount": "2"}')
    expect(loadLastOrder()).toBeNull()

    sessionStorage.setItem('stampede:last-order', 'not json')
    expect(loadLastOrder()).toBeNull()
    expect(sessionStorage.length).toBe(0)
  })

  it('clearLastOrder removes it; clearBookingState removes it together with the holds', () => {
    saveLastOrder(ORDER)
    clearLastOrder()
    expect(loadLastOrder()).toBeNull()

    saveLastOrder(ORDER)
    saveHold({ holdId: 'h', eventId: 'event-1', seatIds: [], expiresAt: '2099-01-01T00:00:00Z', clockOffsetMs: 0 })
    clearBookingState()
    expect(sessionStorage.length).toBe(0)
  })
})
