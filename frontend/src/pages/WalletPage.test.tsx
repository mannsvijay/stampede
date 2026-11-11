import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'

import type { Order, Ticket } from '../api/orders'
import { saveLastOrder } from '../booking/lastOrderStore'
import { DEMO_ID, detail } from '../test/fixtures'
import { renderAt } from '../test/render'
import { problem, server } from '../test/server'

// A real ticket string (docs/ticket-format.md section 5); the page only draws it.
const QR =
  'ARbqCjcRERERERFBEYERERERERERIiIiIiIiQiKCIiIiIiIiIjMzMzMzM0MzgzMzMzMzMzNERERERERERIREREREREREAAAAAGrzB0AAAAAB' +
  '.5_GwMTIhLuHjOFTr-jOb1j7rXMTRC7Q9vvQm19L6dn_O5UJ4yvrtJ5tus0JFTitX5_BC0Ty6sfy5YeMWACjuCg'

const OLD_ORDER = '0aaaaaaa-0000-4000-8000-000000000000'
const NEW_ORDER = '0bbbbbbb-0000-4000-8000-000000000000'

function ticket(n: number, orderId: string, seat: Partial<Ticket>, issuedAt: string): Ticket {
  return {
    ticketId: `00000000-0000-4000-8000-00000000000${n}`,
    orderId,
    eventId: DEMO_ID,
    seatId: `10000000-0000-4000-8000-00000000000${n}`,
    section: 'A',
    row: '1',
    seatNumber: 1,
    status: 'ISSUED',
    version: 1,
    issuedAt,
    qr: QR,
    ...seat,
  }
}

/** A logged-in visitor (the start-up refresh succeeds), their tickets, and the event's title. */
function serveWallet(ticketsPerCall: Ticket[][]) {
  const calls = { tickets: 0 }
  server.use(
    http.post('/auth/refresh', () => HttpResponse.json({ accessToken: 'token-1', tokenType: 'Bearer', expiresIn: 900 })),
    http.get('/events/:id', () => HttpResponse.json(detail())),
    http.get('/me/tickets', () => {
      const items = ticketsPerCall[Math.min(calls.tickets, ticketsPerCall.length - 1)] ?? []
      calls.tickets++
      return HttpResponse.json({ items })
    }),
  )
  return calls
}

const OLD = '2026-11-01T10:00:00Z'
const NEW = '2026-11-12T10:00:00Z'

describe('WalletPage', () => {
  it('sends a logged-out visitor to log in first', async () => {
    const { router } = renderAt('/wallet')
    await waitFor(() => expect(router.state.location.search).toBe('?next=%2Fwallet'))
    expect(router.state.location.pathname).toBe('/login')
  })

  it('groups tickets by order, newest order first, seats in order, each with seat label and QR code', async () => {
    serveWallet([
      [
        ticket(1, OLD_ORDER, { section: 'B', row: '2', seatNumber: 7 }, OLD),
        ticket(2, NEW_ORDER, { section: 'A', row: '10', seatNumber: 1 }, NEW),
        ticket(3, NEW_ORDER, { section: 'A', row: '9', seatNumber: 4 }, NEW),
      ],
    ])
    renderAt('/wallet')

    const orders = await screen.findAllByRole('region')
    // The event title arrives with its own request (one per event).
    await waitFor(() =>
      expect(orders.map((o) => within(o).getByRole('heading').textContent)).toEqual([
        'Stampede Demo Drop Order 0bbbbbbb',
        'Stampede Demo Drop Order 0aaaaaaa',
      ]),
    )
    const newest = within(orders[0]!).getAllByRole('listitem')
    expect(newest.map((t) => within(t).getByRole('img').getAttribute('aria-label'))).toEqual([
      'QR code for Section A · Row 9 · Seat 4', // row 9 before row 10: numeric order
      'QR code for Section A · Row 10 · Seat 1',
    ])
    expect(within(orders[1]!).getByText('Section B · Row 2 · Seat 7')).toBeInTheDocument()
    for (const qr of screen.getAllByRole('img')) {
      expect(qr).toHaveAttribute('data-modules', '57')
    }
    expect(screen.queryByRole('heading', { name: 'Tickets on the way' })).not.toBeInTheDocument()
  })

  it('no tickets: says so', async () => {
    serveWallet([[]])
    renderAt('/wallet')
    expect(await screen.findByText('No tickets yet.')).toBeInTheDocument()
  })

  it('the last order has fewer tickets than seats: "tickets on the way"; Retry re-sends POST /orders for its hold', async () => {
    saveLastOrder({ orderId: NEW_ORDER, holdId: 'hold-9', eventId: DEMO_ID, itemCount: 2 })
    const calls = serveWallet([
      [ticket(1, OLD_ORDER, {}, OLD)], // the new order has none yet
      [ticket(1, OLD_ORDER, {}, OLD), ticket(2, NEW_ORDER, { seatNumber: 1 }, NEW), ticket(3, NEW_ORDER, { seatNumber: 2 }, NEW)],
    ])
    let body: unknown = null
    server.use(
      http.post('/orders', async ({ request }) => {
        body = await request.json()
        const order: Order = {
          orderId: NEW_ORDER,
          holdId: 'hold-9',
          eventId: DEMO_ID,
          status: 'PAID',
          totalPaise: 900_000,
          createdAt: NEW,
          items: [],
        }
        return HttpResponse.json(order, { status: 200 })
      }),
    )
    renderAt('/wallet')

    expect(await screen.findByRole('heading', { name: 'Tickets on the way' })).toBeInTheDocument()
    expect(screen.getByText(/2 tickets are still being issued/)).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Retry' }))

    await waitFor(() => expect(screen.queryByRole('heading', { name: 'Tickets on the way' })).not.toBeInTheDocument())
    expect(body).toEqual({ holdId: 'hold-9' })
    expect(calls.tickets).toBe(2)
    expect(screen.getAllByRole('region')).toHaveLength(2)
  })

  it('a failed Retry shows why and keeps the banner', async () => {
    saveLastOrder({ orderId: NEW_ORDER, holdId: 'hold-9', eventId: DEMO_ID, itemCount: 1 })
    serveWallet([[]])
    server.use(http.post('/orders', () => problem(500, 'INTERNAL_ERROR', 'detail', { errorId: 'err-3' })))
    renderAt('/wallet')

    fireEvent.click(await screen.findByRole('button', { name: 'Retry' }))

    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('Server error. Reference err-3.'))
    expect(screen.getByText(/1 ticket is still being issued/)).toBeInTheDocument()
  })
})
