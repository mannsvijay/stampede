import type { EventDetail, EventLayout, EventSummary, SeatMap } from '../api/client'

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

// A tiny venue for seat map tests: 6 seats. Ids are in byte-wise uuid order, so list position =
// bit index (D-020). Tier ids match detail()'s tiers.
const seatId = (n: number) => `00000000-0000-4000-8000-00000000000${n}`

export const LAYOUT: EventLayout = {
  eventId: DEMO_ID,
  venue: {
    id: VENUE.id,
    name: VENUE.name,
    width: 100,
    height: 50,
    seatRadius: 2,
    stage: { x: 40, y: 2, width: 20, height: 5 },
    sections: [
      { code: 'FL', name: 'Floor Left', label: { x: 20, y: 20 }, outline: [[5, 10], [45, 10], [45, 30], [5, 30]] },
      { code: 'BAL', name: 'Balcony', label: { x: 70, y: 20 }, outline: [[60, 10], [80, 10], [80, 30], [60, 30]] },
    ],
  },
  seats: [
    { id: seatId(1), section: 'FL', row: 'A', number: 1, x: 10, y: 15, tierId: 't-platinum', aisle: true, accessible: false },
    { id: seatId(2), section: 'FL', row: 'A', number: 2, x: 20, y: 15, tierId: 't-platinum', aisle: false, accessible: false },
    { id: seatId(3), section: 'FL', row: 'A', number: 3, x: 30, y: 15, tierId: 't-platinum', aisle: false, accessible: false },
    { id: seatId(4), section: 'FL', row: 'B', number: 1, x: 10, y: 25, tierId: 't-gold', aisle: true, accessible: false },
    { id: seatId(5), section: 'FL', row: 'B', number: 2, x: 20, y: 25, tierId: 't-gold', aisle: false, accessible: true },
    { id: seatId(6), section: 'BAL', row: 'A', number: 1, x: 70, y: 15, tierId: 't-silver', aisle: false, accessible: false },
  ],
}

/** The seat map for LAYOUT: `available[i]` describes LAYOUT.seats[i] (packed as the server does). */
export function seatMapOf(available: boolean[], asOf = new Date().toISOString()): SeatMap {
  const bytes = new Uint8Array(Math.ceil(available.length / 8))
  available.forEach((free, i) => {
    if (free) {
      bytes[i >> 3] = (bytes[i >> 3] ?? 0) | (0x80 >> (i & 7))
    }
  })
  return {
    eventId: DEMO_ID,
    seatCount: available.length,
    available: btoa(String.fromCharCode(...bytes)),
    asOf,
  }
}
