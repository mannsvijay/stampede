import { clockOffsetMs } from '../time/serverClock'
import { api, type WithClock } from './client'
import { ApiError } from './problem'
import type { components } from './schema'

export type Hold = components['schemas']['HoldResponse']

/**
 * POST /events/{id}/holds: all of these seats or none (D-016). 201 is a new hold; 200 means this
 * user already holds exactly these seats (e.g. a retry after a lost response) and returns that hold.
 * `serverTime` corrects the countdown for a laptop clock that is off.
 */
export async function createHold(eventId: string, seatIds: string[]): Promise<WithClock<Hold>> {
  const sentAt = Date.now()
  const { data, error, response } = await api.POST('/events/{id}/holds', {
    params: { path: { id: eventId } },
    body: { seatIds },
  })
  const receivedAt = Date.now()
  if (error !== undefined || data === undefined) {
    throw ApiError.fromBody(response.status, error)
  }
  return { ...data, clockOffsetMs: clockOffsetMs(data.serverTime, sentAt, receivedAt) }
}

/** DELETE /holds/{id}: 204 also when the hold had already been released or expired. */
export async function releaseHold(holdId: string): Promise<void> {
  const { error, response } = await api.DELETE('/holds/{id}', { params: { path: { id: holdId } } })
  if (error !== undefined) {
    throw ApiError.fromBody(response.status, error)
  }
}
