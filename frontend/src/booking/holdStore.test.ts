import { afterEach, describe, expect, it } from 'vitest'

import { clearBookingState, loadHold, saveHold, type StoredHold } from './holdStore'

const NOW = Date.parse('2026-11-12T10:00:00Z')

function hold(overrides: Partial<StoredHold> = {}): StoredHold {
  return {
    holdId: 'hold-1',
    eventId: 'event-1',
    seatIds: ['seat-1', 'seat-2'],
    expiresAt: '2026-11-12T10:05:00Z',
    clockOffsetMs: 0,
    ...overrides,
  }
}

describe('holdStore', () => {
  afterEach(() => sessionStorage.clear())

  it('saves and loads the hold of an event', () => {
    saveHold(hold())

    expect(loadHold('event-1', NOW)).toEqual(hold())
    expect(loadHold('event-2', NOW)).toBeNull()
  })

  it('drops a hold that has expired by the server clock (not the laptop clock)', () => {
    // The laptop is 10 minutes behind the server: by its own clock the hold has 15 minutes left.
    saveHold(hold({ clockOffsetMs: 10 * 60_000 }))

    expect(loadHold('event-1', NOW)).toBeNull()
    expect(sessionStorage.length).toBe(0)
  })

  it('ignores and removes malformed data', () => {
    sessionStorage.setItem('stampede:hold:event-1', '{"holdId": 42}')
    expect(loadHold('event-1', NOW)).toBeNull()

    sessionStorage.setItem('stampede:hold:event-1', 'not json')
    expect(loadHold('event-1', NOW)).toBeNull()
    expect(sessionStorage.length).toBe(0)
  })

  it('clearBookingState removes every stampede: key and nothing else', () => {
    saveHold(hold())
    saveHold(hold({ eventId: 'event-2' }))
    sessionStorage.setItem('stampede:last-order', '{}')
    sessionStorage.setItem('someone-else', 'keep')

    clearBookingState()

    expect(Object.keys(sessionStorage)).toEqual(['someone-else'])
  })
})
