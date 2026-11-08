import { Link } from 'react-router'

import { useEvents } from '../api/client'
import { formatCountdown, remainingMs, useNow } from '../time/serverClock'
import styles from './BoardPage.module.css'
import { ErrorMessage } from './ErrorMessage'
import { statusLabel } from './eventStatus'

/** The departures board (PRD 8.1), plain for now: the split-flap design arrives in M5. */
export function BoardPage() {
  const events = useEvents()
  const now = useNow()

  if (events.isPending) {
    return <p className="label">Loading departures</p>
  }
  if (events.isError) {
    return <ErrorMessage error={events.error} />
  }

  const { items, clockOffsetMs } = events.data
  const next = items.find((e) => e.status === 'SCHEDULED' && remainingMs(e.dropAt, clockOffsetMs, now) > 0)

  return (
    <>
      <section className={styles.hero} aria-label="Next drop">
        <span className="label">{next ? `Next drop · ${next.title}` : 'No upcoming drops'}</span>
        {next && (
          <span className={`mono ${styles.heroCountdown}`} data-testid="next-drop-countdown">
            {formatCountdown(remainingMs(next.dropAt, clockOffsetMs, now))}
          </span>
        )}
      </section>

      {items.length > 0 && (
        <table className={styles.board}>
          <thead>
            <tr>
              <th className="label">Drop</th>
              <th className="label">Venue</th>
              <th className="label">Status</th>
            </tr>
          </thead>
          <tbody>
            {items.map((event) => (
              <tr key={event.id}>
                <td>
                  <Link to={`/drops/${event.id}`} className={styles.title}>
                    {event.title}
                  </Link>
                </td>
                <td>{event.venue.name}</td>
                <td className={`mono ${event.status === 'LIVE' ? styles.live : ''}`}>
                  {statusLabel(event.status, remainingMs(event.dropAt, clockOffsetMs, now))}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </>
  )
}
