import { screen, within } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'

import type { EventList } from '../api/client'
import { summary } from '../test/fixtures'
import { renderAt } from '../test/render'
import { problem, server } from '../test/server'

function serveEvents(body: EventList) {
  server.use(http.get('/events', () => HttpResponse.json(body)))
}

describe('BoardPage', () => {
  it('lists the upcoming drops from GET /events with venue, status and a link to each', async () => {
    const serverTime = new Date().toISOString()
    const inOneHour = new Date(Date.now() + 3_600_000).toISOString()
    serveEvents({
      serverTime,
      items: [
        summary({ id: 'id-scheduled', title: 'Demo Drop', dropAt: inOneHour }),
        summary({ id: 'id-live', title: 'Live Drop', status: 'LIVE' }),
        summary({ id: 'id-sold', title: 'Gone Drop', status: 'SOLD_OUT' }),
      ],
    })

    renderAt('/')

    const rows = await screen.findAllByRole('row')
    expect(rows).toHaveLength(4) // header + 3 events
    expect(screen.getByRole('link', { name: 'Demo Drop' })).toHaveAttribute('href', '/drops/id-scheduled')
    expect(screen.getByRole('link', { name: 'Live Drop' })).toHaveAttribute('href', '/drops/id-live')
    expect(within(rows[1]!).getByText(/^ON SALE IN 0(0:59|1:00):\d\d$/)).toBeInTheDocument()
    expect(within(rows[2]!).getByText('LIVE')).toBeInTheDocument()
    expect(within(rows[3]!).getByText('SOLD OUT')).toBeInTheDocument()
    expect(screen.getAllByText('Stampede Demo Arena')).toHaveLength(3)
    expect(screen.getByText('Next drop · Demo Drop')).toBeInTheDocument()
  })

  it('says OPENING when the drop time has passed but the server has not switched to LIVE yet', async () => {
    serveEvents({
      serverTime: new Date().toISOString(),
      items: [summary({ dropAt: new Date(Date.now() - 1_000).toISOString() })],
    })

    renderAt('/')

    expect(await screen.findByText('OPENING')).toBeInTheDocument()
    expect(screen.getByText('No upcoming drops')).toBeInTheDocument()
  })

  it('shows an empty board when there are no drops', async () => {
    serveEvents({ serverTime: new Date().toISOString(), items: [] })

    renderAt('/')

    expect(await screen.findByText('No upcoming drops')).toBeInTheDocument()
    expect(screen.queryByRole('table')).not.toBeInTheDocument()
  })

  it('shows the error by its code, with the reference for a server error', async () => {
    server.use(http.get('/events', () => problem(500, 'INTERNAL_ERROR', 'x', { errorId: 'abc-123' })))

    renderAt('/')

    expect(await screen.findByRole('alert')).toHaveTextContent('Server error. Reference abc-123.')
  })

  it('needs no token: the request carries no Authorization header', async () => {
    let authorization: string | null = 'not checked'
    server.use(
      http.get('/events', ({ request }) => {
        authorization = request.headers.get('Authorization')
        return HttpResponse.json({ serverTime: new Date().toISOString(), items: [] })
      }),
    )

    renderAt('/')

    await screen.findByText('No upcoming drops')
    expect(authorization).toBeNull()
  })
})
