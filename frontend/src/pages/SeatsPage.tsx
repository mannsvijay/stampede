import { useEffect, useMemo, useState } from 'react'
import { Link, useParams } from 'react-router'

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
import { ApiError } from '../api/problem'
import { clearHold, loadHold, saveHold, type StoredHold } from '../booking/holdStore'
import { formatPaise } from '../format/money'
import { decodeAvailability } from '../seatmap/availability'
import type { SeatState } from '../seatmap/draw'
import { SeatCanvas } from '../seatmap/SeatCanvas'
import { seatName, sectionNames } from '../seatmap/seatNames'
import { formatCountdown, remainingMs, useNow } from '../time/serverClock'
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

  const [pickedIds, setPickedIds] = useState<string[]>([])
  const [hold, setHold] = useState<StoredHold | null>(() => loadHold(eventId))
  const [error, setError] = useState<unknown>(null)
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
      setPickedIds([])
    } catch (cause) {
      setError(cause)
      if (cause instanceof ApiError && cause.code === 'SEAT_TAKEN') {
        setPickedIds(picked.filter((id) => !cause.seatIds.includes(id)))
      }
      // A NETWORK_ERROR keeps the selection: retrying the same seats is safe (D-016).
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

  const msUntilDrop = remainingMs(event.dropAt, event.clockOffsetMs, now)

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
          {error ? holdErrorText(error, { seatName: nameOf, msUntilDrop }) : ''}
        </p>
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
            <ul className={styles.seatList} aria-label="Held seats">
              {hold.seatIds.map((id) => (
                <li key={id}>{nameOf(id)}</li>
              ))}
            </ul>
            <div className={styles.actions}>
              <button type="button" onClick={release} disabled={busy}>
                Release
              </button>
              <button type="button" disabled aria-describedby="checkout-note">
                Continue to checkout
              </button>
            </div>
            <p id="checkout-note" className={styles.hint}>
              Checkout isn't built yet. It arrives in the next update.
            </p>
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
