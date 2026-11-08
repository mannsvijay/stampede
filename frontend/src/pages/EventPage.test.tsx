import { screen, within } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'

import { DEMO_ID, detail } from '../test/fixtures'
import { renderAt } from '../test/render'
import { problem, server } from '../test/server'

describe('EventPage', () => {
  it('shows the venue, per-user limit and tiers with prices in rupees, most expensive first', async () => {
    server.use(http.get(`/events/${DEMO_ID}`, () => HttpResponse.json(detail())))

    renderAt(`/drops/${DEMO_ID}`)

    expect(await screen.findByRole('heading', { name: 'Stampede Demo Drop' })).toBeInTheDocument()
    expect(screen.getByText('Stampede Demo Arena')).toBeInTheDocument()
    expect(screen.getByText('4 tickets per person')).toBeInTheDocument()

    const rows = within(screen.getByRole('table', { name: 'Price tiers' })).getAllByRole('row')
    expect(rows.map((row) => row.textContent)).toEqual(['PLATINUM₹4,500', 'GOLD₹2,500', 'SILVER₹1,200'])
  })

  it('shows "No such drop." for an unknown id (404 EVENT_NOT_FOUND)', async () => {
    server.use(http.get('/events/:id', () => problem(404, 'EVENT_NOT_FOUND')))

    renderAt('/drops/00000000-0000-0000-0000-000000000000')

    expect(await screen.findByRole('alert')).toHaveTextContent('No such drop.')
    expect(screen.getByRole('link', { name: 'Back to departures' })).toHaveAttribute('href', '/')
  })

  it('shows "No such drop." for a malformed id (400 VALIDATION_FAILED)', async () => {
    server.use(http.get('/events/:id', () => problem(400, 'VALIDATION_FAILED')))

    renderAt('/drops/not-a-uuid')

    expect(await screen.findByRole('alert')).toHaveTextContent('No such drop.')
  })
})

describe('routing', () => {
  it('an unknown page shows the not-found page without calling the API', async () => {
    renderAt('/no/such/page') // MSW fails the test on any unhandled request

    expect(await screen.findByRole('alert')).toHaveTextContent('No such page.')
  })
})
