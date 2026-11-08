import type { EventDetail, EventSummary } from '../api/client'

// Typed with the generated API types: if the spec changes shape, these fixtures stop compiling.

export const DEMO_ID = 'a80c4d32-286d-3abd-81f1-b2a5e837a8dd'
export const VENUE = { id: '0b5c1b4e-6f0a-3c8e-9a6e-1f2d3c4b5a69', name: 'Stampede Demo Arena' }

export function summary(overrides: Partial<EventSummary> = {}): EventSummary {
  return {
    id: DEMO_ID,
    title: 'Stampede Demo Drop',
    status: 'SCHEDULED',
    dropAt: '2026-11-10T09:00:00Z',
    startsAt: '2026-11-16T09:00:00Z',
    perUserLimit: 4,
    venue: VENUE,
    ...overrides,
  }
}

export function detail(overrides: Partial<EventDetail> = {}): EventDetail {
  return {
    ...summary(),
    serverTime: '2026-11-09T09:00:00Z',
    tiers: [
      { id: 't-platinum', name: 'PLATINUM', pricePaise: 450_000 },
      { id: 't-gold', name: 'GOLD', pricePaise: 250_000 },
      { id: 't-silver', name: 'SILVER', pricePaise: 120_000 },
    ],
    ...overrides,
  }
}
