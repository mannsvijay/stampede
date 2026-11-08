import { useEffect, useState } from 'react'

/**
 * Countdowns must follow the server's clock, not the laptop's: a laptop that is 10 minutes fast
 * would otherwise show a drop opening 10 minutes early. Every events response carries serverTime.
 *
 * offset = serverTime − (moment the request was sent + moment the response arrived) / 2
 *
 * Using the midpoint assumes the server stamped serverTime halfway through the round trip, which
 * cancels most of the network latency (the same idea NTP uses). Then:
 *   server "now" ≈ Date.now() + offset
 */
export function clockOffsetMs(serverTime: string, sentAt: number, receivedAt: number): number {
  return Date.parse(serverTime) - (sentAt + receivedAt) / 2
}

/** Milliseconds from the server's "now" until the target instant (negative once it has passed). */
export function remainingMs(targetIso: string, offsetMs: number, laptopNow: number = Date.now()): number {
  return Date.parse(targetIso) - (laptopNow + offsetMs)
}

/** "02:14:09", or "1D 02:14:09" when a day or more is left. Never negative. */
export function formatCountdown(ms: number): string {
  const totalSeconds = Math.max(0, Math.floor(ms / 1000))
  const days = Math.floor(totalSeconds / 86_400)
  const hours = Math.floor((totalSeconds % 86_400) / 3_600)
  const minutes = Math.floor((totalSeconds % 3_600) / 60)
  const seconds = totalSeconds % 60
  const hms = [hours, minutes, seconds].map((n) => String(n).padStart(2, '0')).join(':')
  return days > 0 ? `${days}D ${hms}` : hms
}

/** Re-renders the calling component every `intervalMs` and returns the laptop's Date.now(). */
export function useNow(intervalMs = 250): number {
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    const id = setInterval(() => setNow(Date.now()), intervalMs)
    return () => clearInterval(id)
  }, [intervalMs])
  return now
}
