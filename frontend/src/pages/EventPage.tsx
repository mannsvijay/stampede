import { Link, useParams } from 'react-router'

import { useEvent } from '../api/client'
import { formatPaise } from '../format/money'
import { remainingMs, useNow } from '../time/serverClock'
import { ErrorMessage } from './ErrorMessage'
import styles from './EventPage.module.css'
import { statusLabel } from './eventStatus'

const dateTime = new Intl.DateTimeFormat('en-IN', { dateStyle: 'medium', timeStyle: 'short' })

/** The event page (PRD 8.2), plain for now: venue, times, per-user limit and price tiers. */
export function EventPage() {
  const { id = '' } = useParams()
  const event = useEvent(id)
  const now = useNow()

  if (event.isPending) {
    return <p className="label">Loading drop</p>
  }
  if (event.isError) {
    return (
      <>
        <ErrorMessage error={event.error} />
        <Link to="/">Back to departures</Link>
      </>
    )
  }

  const e = event.data
  return (
    <article>
      <span className="label">{e.venue.name}</span>
      <h1 className={styles.title}>{e.title}</h1>

      <dl className={styles.facts}>
        <dt className="label">Status</dt>
        <dd className="mono">{statusLabel(e.status, remainingMs(e.dropAt, e.clockOffsetMs, now))}</dd>
        <dt className="label">Drop</dt>
        <dd className="mono">{dateTime.format(new Date(e.dropAt))}</dd>
        <dt className="label">Starts</dt>
        <dd className="mono">{dateTime.format(new Date(e.startsAt))}</dd>
        <dt className="label">Limit</dt>
        <dd className="mono">{e.perUserLimit} tickets per person</dd>
      </dl>

      {(e.status === 'SCHEDULED' || e.status === 'LIVE') && (
        <p>
          <Link to={`/drops/${e.id}/seats`} className={styles.pick}>
            Pick seats
          </Link>
        </p>
      )}

      <table className={styles.tiers} aria-label="Price tiers">
        <tbody>
          {e.tiers.map((tier) => (
            <tr key={tier.id}>
              <td className="label">{tier.name}</td>
              <td className={`mono ${styles.price}`}>{formatPaise(tier.pricePaise)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </article>
  )
}
