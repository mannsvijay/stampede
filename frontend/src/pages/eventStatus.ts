import type { EventStatus } from '../api/client'
import { formatCountdown } from '../time/serverClock'

/**
 * What the board says about a drop (PRD 8.1): "ON SALE IN 02:14:09" → "LIVE" → "SOLD OUT".
 * The status comes from the server. Once the countdown reaches zero but the server hasn't switched
 * the event to LIVE yet, we say OPENING rather than pretend it is live.
 */
export function statusLabel(status: EventStatus, msUntilDrop: number): string {
  switch (status) {
    case 'SCHEDULED':
      return msUntilDrop > 0 ? `ON SALE IN ${formatCountdown(msUntilDrop)}` : 'OPENING'
    case 'LIVE':
      return 'LIVE'
    case 'PAUSED':
      return 'PAUSED'
    case 'SOLD_OUT':
      return 'SOLD OUT'
    case 'ENDED':
      return 'ENDED'
  }
}
