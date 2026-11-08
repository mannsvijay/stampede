import { http, HttpResponse } from 'msw'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { screen } from '@testing-library/react'

import type { EventList } from '../api/client'
import { summary } from '../test/fixtures'
import { renderAt } from '../test/render'
import { server } from '../test/server'
import { clockOffsetMs, formatCountdown, remainingMs } from './serverClock'

const SERVER_NOW = '2026-11-09T09:00:00Z'
const DROP_AT = '2026-11-09T10:00:00Z' // exactly 1 hour after the server's now
const LAPTOP_IS_FAST_BY = 10 * 60_000 // the laptop's clock says 09:10 when the server says 09:00

describe('countdown follows the server clock, not the laptop clock', () => {
  afterEach(() => {
    vi.useRealTimers()
  })

  it('shows 01:00:00 to a drop one server-hour away even though the laptop clock is 10 minutes fast', async () => {
    // Only Date is faked (timers stay real), so MSW and React Testing Library still work normally.
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(Date.parse(SERVER_NOW) + LAPTOP_IS_FAST_BY)
    const body: EventList = { items: [summary({ dropAt: DROP_AT })], serverTime: SERVER_NOW }
    server.use(http.get('/events', () => HttpResponse.json(body)))

    renderAt('/')

    expect(await screen.findByTestId('next-drop-countdown')).toHaveTextContent('01:00:00')
    expect(screen.getByText('ON SALE IN 01:00:00')).toBeInTheDocument()
    // Guard against a vacuous pass: by the laptop's own clock it would be 50 minutes.
    expect(formatCountdown(remainingMs(DROP_AT, 0))).toBe('00:50:00')
  })
})

describe('clockOffsetMs', () => {
  it('uses the midpoint of the round trip, cancelling symmetric network latency', () => {
    const sentAt = Date.parse('2026-11-09T09:00:00.000Z')
    const receivedAt = sentAt + 400 // 400 ms round trip
    // The server stamped its time halfway through, and its clock is 5 s behind the laptop.
    const serverTime = new Date(sentAt + 200 - 5_000).toISOString()

    expect(clockOffsetMs(serverTime, sentAt, receivedAt)).toBe(-5_000)
  })
})

describe('formatCountdown', () => {
  it.each([
    [0, '00:00:00'],
    [-5_000, '00:00:00'],
    [999, '00:00:00'],
    [61_000, '00:01:01'],
    [2 * 3_600_000 + 14 * 60_000 + 9_000, '02:14:09'],
    [26 * 3_600_000, '1D 02:00:00'],
  ])('%i ms → %s', (ms, expected) => {
    expect(formatCountdown(ms)).toBe(expected)
  })
})
