import { useQuery } from '@tanstack/react-query'
import createClient from 'openapi-fetch'

import { clockOffsetMs } from '../time/serverClock'
import { authFetch } from './http'
import { ApiError } from './problem'
import type { components, paths } from './schema'

export type EventList = components['schemas']['EventList']
export type EventSummary = components['schemas']['EventSummary']
export type EventDetail = components['schemas']['EventDetail']
export type EventStatus = EventSummary['status']
export type EventLayout = components['schemas']['EventLayout']
export type LayoutSeat = components['schemas']['LayoutSeat']
export type SeatMap = components['schemas']['SeatMap']

/** A response plus how far the server's clock is from this laptop's (see serverClock.ts). */
export type WithClock<T> = T & { clockOffsetMs: number }

/**
 * The typed API client. Paths, parameters and response bodies are checked against the types
 * generated from docs/openapi.json, so a renamed field is a compile error, not a runtime surprise.
 */
export const api = createClient<paths>({
  baseUrl: window.location.origin,
  fetch: authFetch,
})

export async function fetchEvents(): Promise<WithClock<EventList>> {
  const sentAt = Date.now()
  const { data, error, response } = await api.GET('/events')
  const receivedAt = Date.now()
  if (error !== undefined || data === undefined) {
    throw ApiError.fromBody(response.status, error)
  }
  return { ...data, clockOffsetMs: clockOffsetMs(data.serverTime, sentAt, receivedAt) }
}

export async function fetchEvent(id: string): Promise<WithClock<EventDetail>> {
  const sentAt = Date.now()
  const { data, error, response } = await api.GET('/events/{id}', { params: { path: { id } } })
  const receivedAt = Date.now()
  if (error !== undefined || data === undefined) {
    throw ApiError.fromBody(response.status, error)
  }
  return { ...data, clockOffsetMs: clockOffsetMs(data.serverTime, sentAt, receivedAt) }
}

/** Where every seat is (static). `seats[i]` is described by bit i of the seat map (D-020). */
export async function fetchLayout(id: string): Promise<EventLayout> {
  const { data, error, response } = await api.GET('/events/{id}/layout', { params: { path: { id } } })
  if (error !== undefined || data === undefined) {
    throw ApiError.fromBody(response.status, error)
  }
  return data
}

/** Which seats are available now, as a bitset in layout order (decode with seatmap/availability.ts). */
export async function fetchSeatMap(id: string): Promise<WithClock<SeatMap>> {
  const sentAt = Date.now()
  const { data, error, response } = await api.GET('/events/{id}/seatmap', { params: { path: { id } } })
  const receivedAt = Date.now()
  if (error !== undefined || data === undefined) {
    throw ApiError.fromBody(response.status, error)
  }
  return { ...data, clockOffsetMs: clockOffsetMs(data.asOf, sentAt, receivedAt) }
}

export function useEvents() {
  return useQuery({ queryKey: ['events'], queryFn: fetchEvents })
}

export function useEvent(id: string) {
  return useQuery({ queryKey: ['events', id], queryFn: () => fetchEvent(id) })
}

/** The layout never changes after an event is created, so it is fetched once and never refetched. */
export function useLayout(id: string) {
  return useQuery({ queryKey: ['events', id, 'layout'], queryFn: () => fetchLayout(id), staleTime: Infinity })
}

/** Refetch with `refetch()` after a rejected hold (no realtime until M5). */
export function useSeatMap(id: string) {
  return useQuery({ queryKey: ['events', id, 'seatmap'], queryFn: () => fetchSeatMap(id) })
}

/** Retry server and network errors twice; a 4xx won't change by retrying. */
export function shouldRetry(failureCount: number, error: unknown): boolean {
  if (error instanceof ApiError && error.status >= 400 && error.status < 500) {
    return false
  }
  return failureCount < 2
}
