import { useQuery } from '@tanstack/react-query'

import { api } from './client'
import { ApiError } from './problem'
import type { components } from './schema'

export type Order = components['schemas']['OrderResponse']
export type Ticket = components['schemas']['TicketResponse']

/**
 * POST /orders: turns a hold into a paid order (D-017). 201 is a new order; 200 means this hold
 * already has one and returns it as it is now (a retry, or a double click). A hold has at most one
 * order ever, so retrying the same hold never charges twice. On a 200 for an order whose tickets
 * weren't issued yet, the server issues them before answering (D-018).
 */
export async function placeOrder(holdId: string): Promise<Order> {
  const { data, error, response } = await api.POST('/orders', { body: { holdId } })
  if (error !== undefined || data === undefined) {
    throw ApiError.fromBody(response.status, error)
  }
  return data
}

/** GET /me/tickets: every ticket of the logged-in user. */
export async function fetchMyTickets(): Promise<Ticket[]> {
  const { data, error, response } = await api.GET('/me/tickets')
  if (error !== undefined || data === undefined) {
    throw ApiError.fromBody(response.status, error)
  }
  return data.items
}

export const MY_TICKETS_KEY = ['me', 'tickets'] as const

export function useMyTickets() {
  return useQuery({ queryKey: MY_TICKETS_KEY, queryFn: fetchMyTickets })
}
