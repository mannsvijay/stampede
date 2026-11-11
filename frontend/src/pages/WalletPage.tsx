import { useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'

import { useEvent } from '../api/client'
import { MY_TICKETS_KEY, placeOrder, type Ticket, useMyTickets } from '../api/orders'
import { groupByOrder, ticketLabel } from '../booking/groupTickets'
import { loadLastOrder } from '../booking/lastOrderStore'
import { TicketQr } from '../qr/TicketQr'
import { checkoutErrorText } from './checkoutErrorText'
import { ErrorMessage } from './ErrorMessage'
import styles from './WalletPage.module.css'

/** /wallet (PRD 8.8, plain for now): the user's tickets with their QR codes, grouped by order. */
export function WalletPage() {
  const tickets = useMyTickets()
  // Read once: it only changes on logout or login, which leave this page.
  const [lastOrder] = useState(loadLastOrder)

  if (tickets.error) {
    return <ErrorMessage error={tickets.error} />
  }
  if (!tickets.data) {
    return <p className="label">Loading tickets</p>
  }

  const groups = groupByOrder(tickets.data)
  const issuedForLast = lastOrder ? tickets.data.filter((t) => t.orderId === lastOrder.orderId).length : 0
  const missing = lastOrder ? lastOrder.itemCount - issuedForLast : 0

  return (
    <article className={styles.page}>
      <h1 className={styles.title}>Wallet</h1>
      {lastOrder && missing > 0 && <TicketsOnTheWay holdId={lastOrder.holdId} missing={missing} />}
      {groups.length === 0 ? (
        <p className={styles.hint}>No tickets yet.</p>
      ) : (
        groups.map((group) => (
          <section key={group.orderId} aria-labelledby={`order-${group.orderId}`} className={styles.order}>
            <h2 id={`order-${group.orderId}`} className={styles.orderTitle}>
              <EventTitle eventId={group.eventId} />{' '}
              <span className="label">Order {group.orderId.slice(0, 8)}</span>
            </h2>
            <ul className={styles.tickets} aria-label={`Tickets of order ${group.orderId.slice(0, 8)}`}>
              {group.tickets.map((ticket) => (
                <TicketCard key={ticket.ticketId} ticket={ticket} />
              ))}
            </ul>
          </section>
        ))
      )}
    </article>
  )
}

function TicketCard({ ticket }: { ticket: Ticket }) {
  const label = ticketLabel(ticket)
  return (
    <li className={styles.ticket}>
      <TicketQr value={ticket.qr} label={`QR code for ${label}`} />
      <div>
        <p className={styles.seat}>{label}</p>
        <p className="label">{ticket.status === 'ISSUED' ? 'Valid' : ticket.status.replace('_', ' ')}</p>
      </div>
    </li>
  )
}

/** The event's title (cached by TanStack Query, so one request per event), or a placeholder. */
function EventTitle({ eventId }: { eventId: string }) {
  const event = useEvent(eventId)
  return <>{event.data?.title ?? 'Drop'}</>
}

/**
 * The last order was paid but has fewer tickets than seats: issuing failed after payment (D-018).
 * Retrying POST /orders for the same hold returns that order (200) and issues the missing tickets.
 */
function TicketsOnTheWay({ holdId, missing }: { holdId: string; missing: number }) {
  const queryClient = useQueryClient()
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<unknown>(null)

  async function retry() {
    setBusy(true)
    setError(null)
    try {
      await placeOrder(holdId)
      await queryClient.invalidateQueries({ queryKey: MY_TICKETS_KEY })
    } catch (cause) {
      setError(cause)
    } finally {
      setBusy(false)
    }
  }

  return (
    <section aria-labelledby="on-the-way" className={styles.onTheWay}>
      <h2 id="on-the-way" className="label">
        Tickets on the way
      </h2>
      <p>
        Your order is paid and the seats are yours. {missing === 1 ? '1 ticket is' : `${missing} tickets are`} still
        being issued.
      </p>
      <p role="alert" className={styles.error}>
        {error ? checkoutErrorText(error, { seatName: () => 'A seat', msUntilDrop: 0 }) : ''}
      </p>
      <button type="button" onClick={retry} disabled={busy}>
        {busy ? 'Retrying…' : 'Retry'}
      </button>
    </section>
  )
}
