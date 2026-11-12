import { describe, expect, it } from 'vitest'

import { ApiError } from '../api/problem'
import { formatAgo, type HoldErrorContext, holdErrorText } from './holdErrorText'

const NAMES: Record<string, string> = { s1: 'Floor Left, row A, seat 2', s2: 'Floor Left, row A, seat 3' }
const context: HoldErrorContext = { seatName: (id) => NAMES[id] ?? id, msUntilDrop: 0 }

function error(status: number, code: ApiError['code'], extra: ConstructorParameters<typeof ApiError>[5] = {}) {
  return new ApiError(status, code, 'detail', [], undefined, extra)
}

describe('holdErrorText', () => {
  it('SEAT_TAKEN names the seat and says how long ago it went, from takenAgoMs', () => {
    expect(holdErrorText(error(409, 'SEAT_TAKEN', { seatIds: ['s1'], takenAgoMs: 40 }), context)).toBe(
      'Floor Left, row A, seat 2 was taken 40 ms ago. Pick another.',
    )
  })

  it('SEAT_TAKEN with several seats, and without takenAgoMs', () => {
    expect(holdErrorText(error(409, 'SEAT_TAKEN', { seatIds: ['s1', 's2'] }), context)).toBe(
      'Floor Left, row A, seat 2 and Floor Left, row A, seat 3 were taken just now. Pick others.',
    )
  })

  it('SEAT_CONTENDED names the contended seats and says the selection is kept', () => {
    expect(holdErrorText(error(409, 'SEAT_CONTENDED', { seatIds: ['s1'] }), context)).toBe(
      "Lots of people are going for Floor Left, row A, seat 2 right now, so we couldn't hold your seats. They're still picked: try again.",
    )
  })

  it('SEAT_CONTENDED without seatIds still reads naturally', () => {
    expect(holdErrorText(error(409, 'SEAT_CONTENDED'), context)).toBe(
      "Lots of people are going for these seats right now, so we couldn't hold your seats. They're still picked: try again.",
    )
  })

  it('LIMIT_EXCEEDED gives the limit and says it counts what you already have', () => {
    expect(holdErrorText(error(409, 'LIMIT_EXCEEDED', { seatIds: ['s1'], limit: 4 }), context)).toBe(
      'You can have at most 4 tickets for this drop, counting seats you already hold or bought.',
    )
  })

  it('SALE_NOT_OPEN before the drop counts down to it', () => {
    const before = { ...context, msUntilDrop: 12 * 60_000 + 3_000 }
    expect(holdErrorText(error(409, 'SALE_NOT_OPEN'), before)).toBe('Sales open in 00:12:03.')
  })

  it('SALE_NOT_OPEN after the drop time (e.g. paused) says it is not on sale', () => {
    expect(holdErrorText(error(409, 'SALE_NOT_OPEN'), context)).toBe("This drop isn't on sale right now.")
  })

  it.each([
    [410, 'HOLD_EXPIRED', 'Your hold expired and the seats were released. Pick again.'],
    [404, 'HOLD_NOT_FOUND', 'That hold no longer exists. Pick your seats again.'],
    [409, 'HOLD_CONVERTED', 'These seats are already bought.'],
    [400, 'VALIDATION_FAILED', 'Some seats no longer exist. Reload the page.'],
    [404, 'EVENT_NOT_FOUND', 'No such drop.'],
    [500, 'INTERNAL_ERROR', 'Server error.'],
    [418, 'REQUEST_FAILED', 'Something went wrong. Try again.'],
  ] as const)('%i %s', (status, code, text) => {
    expect(holdErrorText(error(status, code), context)).toBe(text)
  })

  it('NETWORK_ERROR says retrying is safe (a retry returns the same hold)', () => {
    expect(holdErrorText(ApiError.network(new TypeError('Failed to fetch')), context)).toBe(
      "The server could not be reached. Try again. If your hold went through, you'll get the same hold back.",
    )
  })

  it('INTERNAL_ERROR quotes the errorId so support can find the log line', () => {
    const internal = new ApiError(500, 'INTERNAL_ERROR', 'detail', [], 'err-7f3a')
    expect(holdErrorText(internal, context)).toBe('Server error. Reference err-7f3a.')
  })
})

describe('formatAgo', () => {
  it.each([
    [0, '0 ms'],
    [40, '40 ms'],
    [999, '999 ms'],
    [3_400, '3 s'],
    [125_000, '2 min'],
  ])('%i ms → %s', (ms, text) => {
    expect(formatAgo(ms)).toBe(text)
  })
})
