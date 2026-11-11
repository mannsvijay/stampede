import type { Ticket } from '../api/orders'

export interface TicketGroup {
  orderId: string
  eventId: string
  tickets: Ticket[]
}

/**
 * Tickets grouped by order: the most recently issued order first, and within an order by section,
 * row and seat number (the order a group of friends walks along the row).
 */
export function groupByOrder(tickets: readonly Ticket[]): TicketGroup[] {
  const groups = new Map<string, TicketGroup>()
  const newestFirst = [...tickets].sort((a, b) => Date.parse(b.issuedAt) - Date.parse(a.issuedAt))
  for (const ticket of newestFirst) {
    let group = groups.get(ticket.orderId)
    if (!group) {
      group = { orderId: ticket.orderId, eventId: ticket.eventId, tickets: [] }
      groups.set(ticket.orderId, group)
    }
    group.tickets.push(ticket)
  }
  for (const group of groups.values()) {
    group.tickets.sort(
      (a, b) =>
        a.section.localeCompare(b.section) ||
        a.row.localeCompare(b.row, undefined, { numeric: true }) ||
        a.seatNumber - b.seatNumber,
    )
  }
  return [...groups.values()]
}

/** "Section A · Row 3 · Seat 5", from the label snapshot taken when the ticket was issued (D-018). */
export function ticketLabel(ticket: Ticket): string {
  return `Section ${ticket.section} · Row ${ticket.row} · Seat ${ticket.seatNumber}`
}
