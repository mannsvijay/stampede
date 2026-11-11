import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import { afterEach, describe, expect, it, vi } from 'vitest'

import type { EventDetail } from '../api/client'
import type { Hold } from '../api/holds'
import type { Order } from '../api/orders'
import { loadHold, saveHold } from '../booking/holdStore'
import { loadLastOrder } from '../booking/lastOrderStore'
import { fitTransform } from '../seatmap/geometry'
import { DEMO_ID, detail, LAYOUT, seatMapOf } from '../test/fixtures'
import { renderAt } from '../test/render'
import { problem, server } from '../test/server'

const PAGE = `/drops/${DEMO_ID}/seats`
const ALL_FREE = [true, true, true, true, true, true]
const seat = (i: number) => LAYOUT.seats[i]?.id ?? ''

/**
 * A logged-in visitor (the start-up refresh succeeds) and the three reads the page makes. The seat
 * map answers `seatMaps[n]` on the n-th request (the last one repeats), and counts requests.
 */
function serveSeatsPage(options: { seatMaps?: boolean[][]; event?: Partial<EventDetail> } = {}) {
  const seatMaps = options.seatMaps ?? [ALL_FREE]
  const calls = { seatMap: 0 }
  server.use(
    http.post('/auth/refresh', () => HttpResponse.json({ accessToken: 'token-1', tokenType: 'Bearer', expiresIn: 900 })),
    http.get('/events/:id', () => HttpResponse.json(detail({ serverTime: new Date().toISOString(), ...options.event }))),
    http.get('/events/:id/layout', () => HttpResponse.json(LAYOUT)),
    http.get('/events/:id/seatmap', () => {
      const bits = seatMaps[Math.min(calls.seatMap, seatMaps.length - 1)] ?? ALL_FREE
      calls.seatMap++
      return HttpResponse.json(seatMapOf(bits))
    }),
  )
  return calls
}

function holdResponse(seatIds: string[], serverTime: string, expiresAt: string): Hold {
  return { holdId: 'hold-1', eventId: DEMO_ID, seatIds, serverTime, expiresAt }
}

/** Picks a seat with the keyboard picker (row, then seat, then "Add seat"). */
async function pick(rowKey: string, seatIndex: number) {
  fireEvent.change(await screen.findByLabelText('Row'), { target: { value: rowKey } })
  fireEvent.change(screen.getByLabelText('Seat'), { target: { value: String(seatIndex) } })
  fireEvent.click(screen.getByRole('button', { name: 'Add seat' }))
}

function selectedSeats(): string[] {
  const list = screen.queryByRole('list', { name: 'Selected seats' })
  return list ? within(list).getAllByRole('listitem').map((item) => item.textContent ?? '') : []
}

async function expectAlert(text: string | RegExp) {
  await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent(text))
}

describe('SeatsPage: access and picking', () => {
  it('sends a logged-out visitor to log in first, then back here', async () => {
    const { router } = renderAt(PAGE)

    await waitFor(() => expect(router.state.location.pathname).toBe('/login'))
    expect(router.state.location.search).toBe(`?next=${encodeURIComponent(PAGE)}`)
  })

  it('the event page links to it', async () => {
    serveSeatsPage()
    renderAt(`/drops/${DEMO_ID}`)

    expect(await screen.findByRole('link', { name: 'Pick seats' })).toHaveAttribute('href', PAGE)
  })

  it('caps the selection at the per-user limit and says so', async () => {
    serveSeatsPage({ event: { perUserLimit: 2 } })
    renderAt(PAGE)

    await pick('FL/A', 0)
    await pick('FL/A', 1)
    await pick('FL/A', 2)

    expect(screen.getByRole('status')).toHaveTextContent('You can pick up to 2 seats.')
    expect(selectedSeats()).toHaveLength(2)
    expect(screen.getByRole('heading', { name: 'Your seats (2 of 2)' })).toBeInTheDocument()
  })

  it('lists picked seats with their prices and a total, and Remove takes one out', async () => {
    serveSeatsPage()
    renderAt(PAGE)

    await pick('FL/A', 0) // PLATINUM ₹4,500
    await pick('FL/B', 4) // GOLD ₹2,500

    expect(selectedSeats()[0]).toContain('Floor Left, row A, seat 1')
    expect(selectedSeats()[1]).toContain('Floor Left, row B, seat 2')
    expect(screen.getByText('Total ₹7,000')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Remove Floor Left, row A, seat 1' }))
    expect(selectedSeats()).toHaveLength(1)
  })

  it('the picker offers only free seats', async () => {
    serveSeatsPage({ seatMaps: [[true, false, true, true, true, true]] })
    renderAt(PAGE)

    fireEvent.change(await screen.findByLabelText('Row'), { target: { value: 'FL/A' } })
    const options = within(screen.getByLabelText('Seat')).getAllByRole('option').map((o) => o.textContent)
    expect(options).toEqual(['Choose a seat', 'Seat 1 · PLATINUM · ₹4,500', 'Seat 3 · PLATINUM · ₹4,500'])
  })

  it('clicking the canvas picks the seat under the pointer; a taken seat is refused', async () => {
    serveSeatsPage({ seatMaps: [[true, true, false, true, true, true]] })
    renderAt(PAGE)
    const canvas = await screen.findByRole('img', { name: /Seat map of/ })
    // jsdom has no layout: give the canvas a size, then click where seats are drawn on it.
    vi.spyOn(canvas, 'getBoundingClientRect').mockReturnValue(DOMRect.fromRect({ x: 0, y: 0, width: 400, height: 200 }))
    const t = fitTransform(LAYOUT.venue.width, LAYOUT.venue.height, 400, 200, 8)
    const clickSeat = (i: number) => {
      const s = LAYOUT.seats[i]
      fireEvent.click(canvas, { clientX: (s?.x ?? 0) * t.scale + t.offsetX + 1, clientY: (s?.y ?? 0) * t.scale + t.offsetY })
    }

    clickSeat(1)
    expect(selectedSeats()).toEqual([expect.stringContaining('Floor Left, row A, seat 2')])

    clickSeat(2)
    expect(screen.getByRole('status')).toHaveTextContent('Floor Left, row A, seat 3 is taken.')

    clickSeat(1) // clicking a picked seat again un-picks it
    expect(selectedSeats()).toEqual([])
  })
})

describe('SeatsPage: holding', () => {
  afterEach(() => vi.useRealTimers())

  it('holds the picked seats; the countdown follows the server clock, not the laptop clock', async () => {
    const SERVER_NOW = '2026-11-12T10:00:00Z'
    vi.useFakeTimers({ toFake: ['Date'] }) // Date only; timers stay real so MSW and React work
    vi.setSystemTime(Date.parse(SERVER_NOW) + 10 * 60_000) // the laptop is 10 minutes fast
    serveSeatsPage()
    let body: unknown = null
    server.use(
      http.post('/events/:id/holds', async ({ request }) => {
        body = await request.json()
        return HttpResponse.json(holdResponse([seat(0), seat(1)], SERVER_NOW, '2026-11-12T10:05:00Z'), { status: 201 })
      }),
    )
    renderAt(PAGE)

    await pick('FL/A', 0)
    await pick('FL/A', 1)
    fireEvent.click(screen.getByRole('button', { name: 'Hold 2 seats' }))

    // By the laptop's own clock the hold would already have expired 5 minutes ago.
    expect(await screen.findByTestId('hold-countdown')).toHaveTextContent('00:05:00')
    expect(body).toEqual({ seatIds: [seat(0), seat(1)] })
    const held = within(screen.getByRole('list', { name: 'Held seats' })).getAllByRole('listitem')
    expect(held.map((item) => item.textContent)).toEqual(['Floor Left, row A, seat 1', 'Floor Left, row A, seat 2'])
    expect(screen.getByRole('button', { name: 'Continue to checkout' })).toBeEnabled()
    expect(JSON.parse(sessionStorage.getItem(`stampede:hold:${DEMO_ID}`) ?? 'null')).toMatchObject({
      holdId: 'hold-1',
      seatIds: [seat(0), seat(1)],
      clockOffsetMs: -10 * 60_000,
    })
  })

  it('SEAT_TAKEN: says which seat went and when, refetches the seat map, and drops that seat', async () => {
    const calls = serveSeatsPage({ seatMaps: [ALL_FREE, [true, false, true, true, true, true]] })
    server.use(
      http.post('/events/:id/holds', () =>
        problem(409, 'SEAT_TAKEN', 'detail', { seatIds: [seat(1)], takenAgoMs: 40 }),
      ),
    )
    renderAt(PAGE)
    await pick('FL/A', 0)
    await pick('FL/A', 1)

    fireEvent.click(screen.getByRole('button', { name: 'Hold 2 seats' }))

    await expectAlert('Floor Left, row A, seat 2 was taken 40 ms ago. Pick another.')
    await waitFor(() => expect(calls.seatMap).toBe(2))
    expect(selectedSeats()).toEqual([expect.stringContaining('Floor Left, row A, seat 1')])
  })

  it.each([
    [
      'LIMIT_EXCEEDED',
      () => problem(409, 'LIMIT_EXCEEDED', 'detail', { seatIds: [seat(0)], limit: 4 }),
      'You can have at most 4 tickets for this drop, counting seats you already hold or bought.',
    ],
    [
      'NETWORK_ERROR',
      () => HttpResponse.error(),
      "The server could not be reached. Try again. If your hold went through, you'll get the same hold back.",
    ],
    [
      'INTERNAL_ERROR',
      () => problem(500, 'INTERNAL_ERROR', 'detail', { errorId: 'err-42' }),
      'Server error. Reference err-42.',
    ],
  ])('%s: shows the message, refetches the seat map, keeps the selection', async (_code, respond, text) => {
    const calls = serveSeatsPage()
    server.use(http.post('/events/:id/holds', respond))
    renderAt(PAGE)
    await pick('FL/A', 0)

    fireEvent.click(screen.getByRole('button', { name: 'Hold 1 seat' }))

    await expectAlert(text)
    await waitFor(() => expect(calls.seatMap).toBe(2))
    expect(selectedSeats()).toHaveLength(1)
  })

  it('SALE_NOT_OPEN: says how long until the drop', async () => {
    serveSeatsPage({ event: { dropAt: new Date(Date.now() + 3_600_000).toISOString() } })
    server.use(http.post('/events/:id/holds', () => problem(409, 'SALE_NOT_OPEN')))
    renderAt(PAGE)
    await pick('FL/A', 0)

    fireEvent.click(screen.getByRole('button', { name: 'Hold 1 seat' }))

    await expectAlert(/^Sales open in (00:59:5\d|01:00:00)\.$/)
  })
})

describe('SeatsPage: the hold survives a reload', () => {
  function storeHold(expiresInMs: number) {
    saveHold({
      holdId: 'hold-1',
      eventId: DEMO_ID,
      seatIds: [seat(5)],
      expiresAt: new Date(Date.now() + expiresInMs).toISOString(),
      clockOffsetMs: 0,
    })
  }

  it('restores the countdown and Release from sessionStorage; Release ends the hold', async () => {
    storeHold(3 * 60_000)
    const calls = serveSeatsPage()
    let deleted = ''
    server.use(
      http.delete('/holds/:id', ({ params }) => {
        deleted = String(params.id)
        return new HttpResponse(null, { status: 204 })
      }),
    )
    renderAt(PAGE)

    expect(await screen.findByTestId('hold-countdown')).toHaveTextContent(/^00:0[23]:\d\d$/)
    expect(screen.getByRole('list', { name: 'Held seats' })).toHaveTextContent('Balcony, row A, seat 1')

    fireEvent.click(screen.getByRole('button', { name: 'Release' }))

    await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('Seats released.'))
    expect(deleted).toBe('hold-1')
    expect(sessionStorage.length).toBe(0)
    await waitFor(() => expect(calls.seatMap).toBe(2))
    expect(screen.getByLabelText('Row')).toBeEnabled() // picking again
  })

  it('Release answered HOLD_NOT_FOUND: the stored hold is dropped and the message says so', async () => {
    storeHold(3 * 60_000)
    serveSeatsPage()
    server.use(http.delete('/holds/:id', () => problem(404, 'HOLD_NOT_FOUND')))
    renderAt(PAGE)

    fireEvent.click(await screen.findByRole('button', { name: 'Release' }))

    await expectAlert('That hold no longer exists. Pick your seats again.')
    expect(sessionStorage.length).toBe(0)
    expect(screen.queryByTestId('hold-countdown')).not.toBeInTheDocument()
  })

  it('when the countdown runs out, the hold is forgotten and the seat map refetched', async () => {
    storeHold(300) // 0.3 s left
    const calls = serveSeatsPage()
    renderAt(PAGE)

    expect(await screen.findByTestId('hold-countdown')).toBeInTheDocument()
    await waitFor(() =>
      expect(screen.getByRole('status')).toHaveTextContent('Your hold expired and the seats were released. Pick again.'),
    )
    expect(sessionStorage.length).toBe(0)
    await waitFor(() => expect(calls.seatMap).toBe(2))
  })
})

describe('SeatsPage: checkout', () => {
  // A held pair: FL A1 (PLATINUM ₹4,500) and BAL A1 (SILVER ₹1,200).
  function storeHeldPair() {
    saveHold({
      holdId: 'hold-1',
      eventId: DEMO_ID,
      seatIds: [seat(0), seat(5)],
      expiresAt: new Date(Date.now() + 3 * 60_000).toISOString(),
      clockOffsetMs: 0,
    })
  }

  function order(overrides: Partial<Order> = {}): Order {
    return {
      orderId: 'order-1',
      holdId: 'hold-1',
      eventId: DEMO_ID,
      status: 'PAID',
      totalPaise: 570_000,
      createdAt: new Date().toISOString(),
      items: [
        { seatId: seat(0), pricePaise: 450_000 },
        { seatId: seat(5), pricePaise: 120_000 },
      ],
      ...overrides,
    }
  }

  /** What the wallet loads after a successful checkout. */
  function serveWallet() {
    server.use(
      http.get('/me/tickets', () => HttpResponse.json({ items: [] })),
      http.get('/events/:id', () => HttpResponse.json(detail())),
    )
  }

  /** Renders the page with the stored hold and opens the receipt. */
  async function openCheckout() {
    const rendered = renderAt(PAGE)
    fireEvent.click(await screen.findByRole('button', { name: 'Continue to checkout' }))
    await screen.findByRole('list', { name: 'Receipt' })
    return rendered
  }

  it('shows a receipt of the held seats with prices and the total; Back returns to the hold', async () => {
    storeHeldPair()
    serveSeatsPage()
    await openCheckout()

    const lines = within(screen.getByRole('list', { name: 'Receipt' }))
      .getAllByRole('listitem')
      .map((item) => item.textContent)
    expect(lines).toEqual(['Floor Left, row A, seat 1 PLATINUM₹4,500', 'Balcony, row A, seat 1 SILVER₹1,200'])
    expect(screen.getByTestId('receipt-total')).toHaveTextContent('Total₹5,700')
    expect(screen.getByTestId('hold-countdown')).toBeInTheDocument() // the clock keeps running
    fireEvent.click(screen.getByRole('button', { name: 'Back' }))
    expect(screen.getByRole('button', { name: 'Release' })).toBeInTheDocument()
  })

  it.each([
    ['201 (new order)', 201],
    ['200 (this hold already had an order)', 200],
  ])('%s: sends the hold id, stores the last order, forgets the hold and opens the wallet', async (_name, status) => {
    storeHeldPair()
    serveSeatsPage()
    let body: unknown = null
    server.use(
      http.post('/orders', async ({ request }) => {
        body = await request.json()
        return HttpResponse.json(order(), { status })
      }),
    )
    const { router } = await openCheckout()
    serveWallet()

    fireEvent.click(screen.getByRole('button', { name: 'Pay ₹5,700' }))

    await waitFor(() => expect(router.state.location.pathname).toBe('/wallet'))
    expect(await screen.findByText('No tickets yet.')).toBeInTheDocument() // the wallet loaded
    expect(body).toEqual({ holdId: 'hold-1' })
    expect(loadHold(DEMO_ID)).toBeNull()
    expect(loadLastOrder()).toEqual({ orderId: 'order-1', holdId: 'hold-1', eventId: DEMO_ID, itemCount: 2 })
  })

  it.each([
    ['410 HOLD_EXPIRED', 410, 'HOLD_EXPIRED', 'Your hold expired and the seats were released. Pick again.'],
    [
      '402 PAYMENT_FAILED',
      402,
      'PAYMENT_FAILED',
      'Payment declined. Nothing was charged, and your seats were released. Pick again.',
    ],
    ['404 HOLD_NOT_FOUND', 404, 'HOLD_NOT_FOUND', 'That hold no longer exists. Pick your seats again.'],
  ] as const)('%s: says so, forgets the hold and refetches the seat map', async (_name, status, code, text) => {
    storeHeldPair()
    const calls = serveSeatsPage()
    server.use(http.post('/orders', () => problem(status, code)))
    await openCheckout()

    fireEvent.click(screen.getByRole('button', { name: 'Pay ₹5,700' }))

    await expectAlert(text)
    expect(loadHold(DEMO_ID)).toBeNull()
    expect(loadLastOrder()).toBeNull()
    expect(screen.queryByTestId('hold-countdown')).not.toBeInTheDocument()
    await waitFor(() => expect(calls.seatMap).toBe(2))
    expect(screen.getByLabelText('Row')).toBeEnabled() // picking again
  })

  it('200 with a CANCELLED order (an earlier attempt failed): treated like an expired hold', async () => {
    storeHeldPair()
    const calls = serveSeatsPage()
    server.use(http.post('/orders', () => HttpResponse.json(order({ status: 'CANCELLED' }))))
    await openCheckout()

    fireEvent.click(screen.getByRole('button', { name: 'Pay ₹5,700' }))

    await waitFor(() =>
      expect(screen.getByRole('status')).toHaveTextContent(
        'That checkout was cancelled and your seats were released. Pick again.',
      ),
    )
    expect(loadHold(DEMO_ID)).toBeNull()
    expect(loadLastOrder()).toBeNull()
    await waitFor(() => expect(calls.seatMap).toBe(2))
  })

  it.each([
    [
      '409 SEAT_TAKEN',
      () => problem(409, 'SEAT_TAKEN', 'detail', { seatIds: [seat(0)] }),
      'Another checkout is still finishing with one of these seats. Try again in a few seconds.',
    ],
    [
      'NETWORK_ERROR',
      () => HttpResponse.error(),
      "The server could not be reached. Try again: you won't be charged twice for these seats.",
    ],
  ])('%s: keeps the hold and offers Retry, which pays again for the same hold', async (_name, respond, text) => {
    storeHeldPair()
    serveSeatsPage()
    const bodies: unknown[] = []
    server.use(
      http.post('/orders', async ({ request }) => {
        bodies.push(await request.json())
        return bodies.length === 1 ? respond() : HttpResponse.json(order(), { status: 201 })
      }),
    )
    const { router } = await openCheckout()
    serveWallet()

    fireEvent.click(screen.getByRole('button', { name: 'Pay ₹5,700' }))

    await expectAlert(text)
    expect(loadHold(DEMO_ID)).not.toBeNull()
    expect(screen.getByTestId('hold-countdown')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Retry' }))

    await waitFor(() => expect(router.state.location.pathname).toBe('/wallet'))
    expect(await screen.findByText('No tickets yet.')).toBeInTheDocument() // the wallet loaded
    expect(bodies).toEqual([{ holdId: 'hold-1' }, { holdId: 'hold-1' }])
  })

  it('500: shows the error reference and keeps the hold', async () => {
    storeHeldPair()
    serveSeatsPage()
    server.use(http.post('/orders', () => problem(500, 'INTERNAL_ERROR', 'detail', { errorId: 'err-7' })))
    await openCheckout()

    fireEvent.click(screen.getByRole('button', { name: 'Pay ₹5,700' }))

    await expectAlert('Server error. Reference err-7.')
    expect(loadHold(DEMO_ID)).not.toBeNull()
  })
})
