import { http, HttpResponse } from 'msw'
import { describe, expect, it, vi } from 'vitest'

import { problem, server } from '../test/server'
import { type EventLayout, fetchLayout, fetchSeatMap, type SeatMap } from './client'
import { ApiError } from './problem'

const EVENT = '11111111-1111-4111-8111-111111111111'

describe('seat map client', () => {
  it('fetches the layout from /events/{id}/layout without a token', async () => {
    const layout: EventLayout = {
      eventId: EVENT,
      venue: {
        id: '22222222-2222-4222-8222-222222222222',
        name: 'Arena',
        width: 200,
        height: 100,
        seatRadius: 4,
        stage: { x: 50, y: 5, width: 100, height: 10 },
        sections: [],
      },
      seats: [],
    }
    let authorization: string | null = 'unset'
    server.use(
      http.get(`*/events/${EVENT}/layout`, ({ request }) => {
        authorization = request.headers.get('Authorization')
        return HttpResponse.json(layout)
      }),
    )

    await expect(fetchLayout(EVENT)).resolves.toEqual(layout)
    expect(authorization).toBeNull()
  })

  it('corrects the clock with the seat map asOf', async () => {
    vi.spyOn(Date, 'now').mockReturnValue(Date.parse('2026-11-11T10:00:00Z'))
    const seatMap: SeatMap = { eventId: EVENT, seatCount: 5, available: '+A==', asOf: '2026-11-11T10:00:30Z' }
    server.use(http.get(`*/events/${EVENT}/seatmap`, () => HttpResponse.json(seatMap)))

    const result = await fetchSeatMap(EVENT)

    expect(result.available).toBe('+A==')
    expect(result.clockOffsetMs).toBe(30_000)
  })

  it('turns a 404 into an ApiError with the code', async () => {
    server.use(http.get(`*/events/${EVENT}/seatmap`, () => problem(404, 'EVENT_NOT_FOUND')))

    const error = await fetchSeatMap(EVENT).catch((e: unknown) => e)

    expect(error).toBeInstanceOf(ApiError)
    expect((error as ApiError).code).toBe('EVENT_NOT_FOUND')
  })
})
