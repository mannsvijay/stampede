import { useQueryClient } from '@tanstack/react-query'
import { useEffect, useMemo, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router'

import {
  type EventDetail,
  type EventLayout,
  type LayoutSeat,
  type SeatMap,
  useEvent,
  useLayout,
  useSeatMap,
  type WithClock,
} from '../api/client'
import { createHold, releaseHold } from '../api/holds'
import { MY_TICKETS_KEY, placeOrder } from '../api/orders'
import { ApiError } from '../api/problem'
import { clearHold, loadHold, saveHold, type StoredHold } from '../booking/holdStore'
import { saveLastOrder } from '../booking/lastOrderStore'
import { formatPaise } from '../format/money'
import { decodeAvailability } from '../seatmap/availability'
import type { SeatState } from '../seatmap/draw'
import { SeatCanvas } from '../seatmap/SeatCanvas'
import { seatName, sectionNames } from '../seatmap/seatNames'
import { formatCountdown, remainingMs, useNow } from '../time/serverClock'
import { CheckoutReceipt, type ReceiptLine } from './CheckoutReceipt'
import { checkoutErrorText, isRetryableCheckoutError } from './checkoutErrorText'
import { ErrorMessage } from './ErrorMessage'
import { holdErrorText } from './holdErrorText'
import { SeatPicker } from './SeatPicker'
import styles from './SeatsPage.module.css'

/** The API takes at most 20 seats per hold (HoldSeatsRequest.maxItems). */
const MAX_SEATS_PER_HOLD = 20

/** /drops/:id/seats (PRD 8.4, plain for now): pick seats on the map or with the keyboard, then hold them. */
export function SeatsPage() {
  const { id = '' } = useParams()
  const event = useEvent(id)
  const layout = useLayout(id)
  const seatMap = useSeatMap(id)

  const failed = event.error ?? layout.error ?? seatMap.error
  if (failed) {
    return (
      <>
        <ErrorMessage error={failed} />
        <Link to={`/drops/${id}`}>Back to the drop</Link>
      </>
    )
  }
  if (!event.data || !layout.data || !seatMap.data) {
    return <p className="label">Loading seats</p>
  }
  return (
    <SeatsView
      event={event.data}
      layout={layout.data}
      seatMap={seatMap.data}
      refetchSeatMap={seatMap.refetch}
    />
  )
}

interface SeatsViewProps {
  event: WithClock<EventDetail>
  layout: EventLayout
  seatMap: WithClock<SeatMap>
  /** TanStack Query's refetch: the same function on every render, so effects don't restart. */
  refetchSeatMap: () => Promise<unknown>
}

function SeatsView({ event, layout, seatMap, refetchSeatMap }: SeatsViewProps) {
  const eventId = event.id
  const seats = layout.seats
  const now = useNow()
  const navigate = useNavigate()
  const queryClient = useQueryClient()

  const [pickedIds, setPickedIds] = useState<string[]>([])
  const [hold, setHold] = useState<StoredHold | null>(() => loadHold(eventId))
  const [error, setError] = useState<unknown>(null)
  /** Which request the error came from: checkout explains some codes differently. */
  const [errorDuring, setErrorDuring] = useState<'hold' | 'checkout'>('hold')
  const [checkingOut, setCheckingOut] = useState(false)
  const [notice, setNotice] = useState('')
  const [busy, setBusy] = useState(false)

  const names = useMemo(() => sectionNames(layout), [layout])
  const indexById = useMemo(() => new Map(seats.map((seat, i) => [seat.id, i])), [seats])
  const seatById = useMemo(() => new Map(seats.map((seat) => [seat.id, seat])), [seats])
  const tiers = useMemo(() => new Map(event.tiers.map((tier) => [tier.id, tier])), [event.tiers])
  const available = useMemo(() => decodeOrNull(seatMap, seats.length), [seatMap, seats.length])
  const cap = Math.min(event.perUserLimit, MAX_SEATS_PER_HOLD)

  // Picked seats a newer seat map shows as taken drop out of the selection (computed, not stored).
  const picked = useMemo(
    () => (available ? pickedIds.filter((id) => available[indexById.get(id) ?? -1]) : []),
    [available, pickedIds, indexById],
  )

  const states = useMemo<SeatState[]>(() => {
    const mine = new Set(hold ? hold.seatIds : picked)
    return seats.map((seat, i) => (mine.has(seat.id) ? 'mine' : available?.[i] ? 'available' : 'taken'))
  }, [seats, hold, picked, available])

  // When the hold runs out, forget it and show the seats as they are now.
  useEffect(() => {
    if (!hold) {
      return
    }
    const timer = setTimeout(
      () => {
        clearHold(eventId)
        setHold(null)
        setNotice('Your hold expired and the seats were released. Pick again.')
        void refetchSeatMap()
      },
      Math.max(0, remainingMs(hold.expiresAt, hold.clockOffsetMs)),
    )
    return () => clearTimeout(timer)
  }, [hold, eventId, refetchSeatMap])

  if (!available) {
    return <p role="alert">The seat map is out of date. Reload the page.</p>
  }

  const nameOf = (seatId: string) => {
    const seat = seatById.get(seatId)
    return seat ? seatName(seat, names) : 'A seat'
  }
  const priceText = (seat: LayoutSeat) => {
    const tier = tiers.get(seat.tierId)
    return tier ? `${tier.name} · ${formatPaise(tier.pricePaise)}` : ''
  }
  const priceOf = (seatId: string) => tiers.get(seatById.get(seatId)?.tierId ?? '')?.pricePaise ?? 0
  const totalPaise = picked.reduce((sum, id) => sum + priceOf(id), 0)

  function toggle(seatIndex: number) {
    if (hold || busy) {
      return
    }
    const seat = seats[seatIndex]
    if (!seat) {
      return
    }
    if (picked.includes(seat.id)) {
      setPickedIds(picked.filter((id) => id !== seat.id))
      setNotice('')
    } else if (!available?.[seatIndex]) {
      setNotice(`${seatName(seat, names)} is taken.`)
    } else if (picked.length >= cap) {
      setNotice(`You can pick up to ${cap} seats.`)
    } else {
      setPickedIds([...picked, seat.id])
      setNotice('')
    }
  }

  async function holdSeats() {
    setBusy(true)
    setError(null)
    setErrorDuring('hold')
    setNotice('')
    try {
      const made = await createHold(eventId, picked)
      const stored: StoredHold = {
        holdId: made.holdId,
        eventId,
        seatIds: made.seatIds,
        expiresAt: made.expiresAt,
        clockOffsetMs: made.clockOffsetMs,
      }
      saveHold(stored)
      setHold(stored)
      setCheckingOut(false)
      setPickedIds([])
    } catch (cause) {
      setError(cause)
      if (cause instanceof ApiError && cause.code === 'SEAT_TAKEN') {
        setPickedIds(picked.filter((id) => !cause.seatIds.includes(id)))
      }
      // A NETWORK_ERROR keeps the selection: retrying the same seats is safe (D-016). So does
      // SEAT_CONTENDED: nothing was held and the seats may still be free (D-021); the page offers Retry.
    } finally {
      setBusy(false)
    }
    // After a success the held seats show as taken to everyone; after a rejection, show what changed.
    void refetchSeatMap()
  }

  async function release() {
    if (!hold) {
      return
    }
    setBusy(true)
    setError(null)
    setErrorDuring('hold')
    try {
      await releaseHold(hold.holdId)
      clearHold(eventId)
      setHold(null)
      setNotice('Seats released.')
    } catch (cause) {
      setError(cause)
      if (cause instanceof ApiError && cause.code === 'HOLD_NOT_FOUND') {
        clearHold(eventId) // not ours, or gone: nothing left to release
        setHold(null)
      }
    } finally {
      setBusy(false)
    }
    void refetchSeatMap()
  }

  /** The hold is over (expired, released by a failed checkout, or not found): forget it, show the map as it is. */
  function dropHold(message = '') {
    clearHold(eventId)
    setHold(null)
    setCheckingOut(false)
    setNotice(message)
    void refetchSeatMap()
  }

  /**
   * POST /orders for the current hold (D-017). Success (201 new, or 200 for an order this hold
   * already has) goes to the wallet, which shows "tickets on the way" if they aren't issued yet.
   */
  async function pay() {
    if (!hold) {
      return
    }
    setBusy(true)
    setError(null)
    setErrorDuring('checkout')
    setNotice('')
    try {
      const order = await placeOrder(hold.holdId)
      if (order.status === 'CANCELLED' || order.status === 'REFUNDED') {
        // A 200 for an earlier attempt on this hold that failed meanwhile: its seats were released.
        dropHold('That checkout was cancelled and your seats were released. Pick again.')
        return
      }
      clearHold(eventId) // CONVERTED into this order: nothing left to release
      saveLastOrder({ orderId: order.orderId, holdId: order.holdId, eventId, itemCount: order.items.length })
      void queryClient.invalidateQueries({ queryKey: MY_TICKETS_KEY })
      await navigate('/wallet')
    } catch (cause) {
      setError(cause)
      if (cause instanceof ApiError) {
        switch (cause.code) {
          case 'HOLD_EXPIRED': // the hold ended before it became an order
          case 'PAYMENT_FAILED': // the server cancelled the order and released the hold
          case 'HOLD_NOT_FOUND': // not ours, or gone
            dropHold()
            break
        }
      }
      // SEAT_TAKEN and NETWORK_ERROR keep the hold: paying again for it is safe (Retry).
    } finally {
      setBusy(false)
    }
  }

  const receiptLines: ReceiptLine[] = hold
    ? hold.seatIds.map((seatId) => {
        const tier = tiers.get(seatById.get(seatId)?.tierId ?? '')
        return { seatId, name: nameOf(seatId), tierName: tier?.name ?? '', pricePaise: tier?.pricePaise ?? 0 }
      })
    : []

  const msUntilDrop = remainingMs(event.dropAt, event.clockOffsetMs, now)
  const errorContext = { seatName: nameOf, msUntilDrop }
  /** A hold that lost its races (SEAT_CONTENDED) can be tried again as is; picked seats taken meanwhile have dropped out. */
  const canRetryHold =
    !hold &&
    errorDuring === 'hold' &&
    error instanceof ApiError &&
    error.code === 'SEAT_CONTENDED' &&
    picked.length > 0

  return (
    <article className={styles.page}>
      <Link to={`/drops/${eventId}`} className="label">
        {event.title}
      </Link>
      <h1 className={styles.title}>Pick seats</h1>

      <SeatCanvas
        layout={layout}
        states={states}
        onSeatClick={toggle}
        label={`Seat map of ${layout.venue.name}. Your seats are marked in red. To choose seats with the keyboard, use the seat picker below.`}
      />

      <div className={styles.panel}>
        <p role="alert" className={styles.error}>
          {error
            ? errorDuring === 'checkout'
              ? checkoutErrorText(error, errorContext)
              : holdErrorText(error, errorContext)
            : ''}
        </p>
        {canRetryHold && (
          <button type="button" onClick={holdSeats} disabled={busy}>
            Try again
          </button>
        )}
        <p role="status" className={styles.notice}>
          {notice}
        </p>

        {hold ? (
          <section aria-labelledby="hold-heading">
            <h2 id="hold-heading" className="label">
              Held for you
            </h2>
            <p className={styles.countdown}>
              <span className="mono" data-testid="hold-countdown">
                {formatCountdown(remainingMs(hold.expiresAt, hold.clockOffsetMs, now))}
              </span>{' '}
              left to check out
            </p>
            {checkingOut ? (
              <CheckoutReceipt
                lines={receiptLines}
                busy={busy}
                retry={isRetryableCheckoutError(error)}
                onPay={pay}
                onBack={() => {
                  setCheckingOut(false)
                  setError(null)
                }}
              />
            ) : (
              <>
                <ul className={styles.seatList} aria-label="Held seats">
                  {hold.seatIds.map((id) => (
                    <li key={id}>{nameOf(id)}</li>
                  ))}
                </ul>
                <div className={styles.actions}>
                  <button type="button" onClick={release} disabled={busy}>
                    Release
                  </button>
                  <button type="button" onClick={() => setCheckingOut(true)} disabled={busy}>
                    Continue to checkout
                  </button>
                </div>
              </>
            )}
          </section>
        ) : (
          <>
            <SeatPicker
              seats={seats}
              sectionOrder={layout.venue.sections}
              isPickable={(i) => available[i] === true && !picked.includes(seats[i]?.id ?? '')}
              priceText={priceText}
              onAdd={toggle}
              disabled={busy}
            />
            <section aria-labelledby="picked-heading">
              <h2 id="picked-heading" className="label">
                Your seats ({picked.length} of {cap})
              </h2>
              {picked.length === 0 ? (
                <p className={styles.hint}>No seats picked yet. Click seats on the map or use the picker.</p>
              ) : (
                <ul className={styles.seatList} aria-label="Selected seats">
                  {picked.map((id) => {
                    const seat = seatById.get(id)
                    return (
                      <li key={id}>
                        {nameOf(id)} <span className={styles.hint}>{seat ? priceText(seat) : ''}</span>{' '}
                        <button
                          type="button"
                          onClick={() => toggle(indexById.get(id) ?? -1)}
                          aria-label={`Remove ${nameOf(id)}`}
                        >
                          Remove
                        </button>
                      </li>
                    )
                  })}
                </ul>
              )}
              <p className="mono">Total {formatPaise(totalPaise)}</p>
              <button type="button" onClick={holdSeats} disabled={busy || picked.length === 0}>
                {busy ? 'Holding…' : `Hold ${picked.length === 1 ? '1 seat' : `${picked.length} seats`}`}
              </button>
            </section>
          </>
        )}
      </div>
    </article>
  )
}

/** The availability bits, or null if the seat map doesn't match the layout (a stale cached layout). */
function decodeOrNull(seatMap: SeatMap, seatCount: number): boolean[] | null {
  if (seatMap.seatCount !== seatCount) {
    return null
  }
  try {
    return decodeAvailability(seatMap.available, seatCount)
  } catch {
    return null
  }
}
