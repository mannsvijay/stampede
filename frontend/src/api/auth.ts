import createClient from 'openapi-fetch'

import { clearBookingState } from '../booking/holdStore'
import { ApiError } from './problem'
import type { paths } from './schema'

/**
 * Session state and the /auth calls (D-008, D-011).
 *
 * The access token lives only in this module's memory: never localStorage, so an XSS payload can't
 * read a stored copy, and a page reload simply needs a refresh. The refresh token is an httpOnly
 * cookie the browser sends by itself; JavaScript never sees it.
 */

/** Required on /auth/refresh and /auth/logout: a CSRF guard (a cross-site form can't set it). */
export const CLIENT_HEADER = { 'X-Requested-With': 'stampede-web' }

/** Name of the browser-wide lock that serialises refreshes across tabs. */
export const REFRESH_LOCK = 'stampede-auth-refresh'

// /auth calls use a plain client without the 401 → refresh logic, so a failing refresh can't
// trigger another refresh. `fetch` is looked up on every call (not captured once) so test mocks
// installed later still apply.
const authClient = createClient<paths>({
  baseUrl: window.location.origin,
  fetch: (request) => globalThis.fetch(request),
})

/**
 * Whether there is a session, for the UI.
 * - `restoring`: the page just loaded and the one start-up refresh hasn't answered yet. Pages that
 *   need a user wait instead of redirecting, or every reload of such a page would bounce to /login.
 * - `loggedIn` / `loggedOut`: there is / isn't an access token in memory.
 */
export type SessionStatus = 'restoring' | 'loggedIn' | 'loggedOut'

let accessToken: string | null = null
let status: SessionStatus = 'restoring'
let refreshInFlight: Promise<string> | null = null
let restoreOnce: Promise<void> | null = null
const listeners = new Set<() => void>()

export function getAccessToken(): string | null {
  return accessToken
}

/** The only place the token changes, so the session status and its listeners always follow it. */
export function setAccessToken(token: string | null): void {
  accessToken = token
  setStatus(token === null ? 'loggedOut' : 'loggedIn')
}

export function getSessionStatus(): SessionStatus {
  return status
}

/** Calls `listener` whenever the session status changes; returns the unsubscribe function. */
export function subscribeSession(listener: () => void): () => void {
  listeners.add(listener)
  return () => listeners.delete(listener)
}

function setStatus(next: SessionStatus): void {
  if (next !== status) {
    status = next
    listeners.forEach((listener) => listener())
  }
}

/**
 * Restores the session after a page load with ONE refresh call: the httpOnly cookie survives a
 * reload, the in-memory access token doesn't. Calling it again returns the same promise, so React
 * StrictMode running effects twice still sends one request. Never rejects: any failure means
 * "logged out" (INVALID_REFRESH_TOKEN is the normal answer for a visitor with no session).
 */
export function restoreSession(): Promise<void> {
  restoreOnce ??= (async () => {
    if (accessToken !== null) {
      setStatus('loggedIn') // already logged in this page load: nothing to restore
      return
    }
    try {
      await refreshAccessToken()
    } catch {
      setAccessToken(null)
    }
  })()
  return restoreOnce
}

/** Back to the state of a fresh page load. For tests. */
export function resetSession(): void {
  accessToken = null
  restoreOnce = null
  setStatus('restoring')
}

export async function login(email: string, password: string): Promise<void> {
  const { data, error, response } = await send(() => authClient.POST('/auth/login', { body: { email, password } }))
  if (error !== undefined || data === undefined) {
    throw ApiError.fromBody(response.status, error)
  }
  // A login in this tab may be a different person from whoever used it before (a shared computer):
  // drop the previous person's hold before the new session starts, so no page can show it. A
  // session restored by refresh is the same person and keeps it. Register logs in through here too.
  clearBookingState()
  setAccessToken(data.accessToken)
}

/** Creates the account. It does not log in (the API returns no token); call {@link login} next. */
export async function register(email: string, password: string): Promise<void> {
  const { error, response } = await send(() => authClient.POST('/auth/register', { body: { email, password } }))
  if (error !== undefined) {
    throw ApiError.fromBody(response.status, error)
  }
}

/**
 * Ends the session on the server (revokes the refresh token, clears the cookie) and forgets the
 * access token. The token is forgotten even if the request fails; the error is still thrown, because
 * the cookie may then still be valid and a reload would log the user back in.
 */
export async function logout(): Promise<void> {
  try {
    const { error, response } = await send(() => authClient.POST('/auth/logout', { headers: CLIENT_HEADER }))
    if (error !== undefined) {
      throw ApiError.fromBody(response.status, error)
    }
  } finally {
    setAccessToken(null)
  }
}

/** fetch rejects (instead of resolving) when the server can't be reached: make that an ApiError too. */
async function send<T>(call: () => Promise<T>): Promise<T> {
  try {
    return await call()
  } catch (cause) {
    throw ApiError.network(cause)
  }
}

/**
 * Gets a new access token, making sure only ONE refresh request is ever running at a time.
 *
 * Refresh tokens rotate, and presenting a used one is treated as theft: the whole session is
 * revoked (D-008). So if three requests get a 401 at the same moment and each refreshed on its own,
 * the second and third would present the already-used cookie and log the user out. Instead, the
 * first caller starts the refresh and every other caller awaits the same promise.
 *
 * Tabs are separate JavaScript worlds, so the promise can't be shared between them. The Web Locks
 * API provides a lock shared by every tab of this origin: a second tab waits, then sends the cookie
 * the first tab just rotated (cookies are shared), which is valid, not a reuse.
 */
export function refreshAccessToken(): Promise<string> {
  refreshInFlight ??= withCrossTabLock(doRefresh).finally(() => {
    refreshInFlight = null
  })
  return refreshInFlight
}

async function doRefresh(): Promise<string> {
  const { data, error, response } = await send(() => authClient.POST('/auth/refresh', { headers: CLIENT_HEADER }))
  if (error !== undefined || data === undefined) {
    setAccessToken(null)
    throw ApiError.fromBody(response.status, error)
  }
  setAccessToken(data.accessToken)
  return data.accessToken
}

function withCrossTabLock<T>(task: () => Promise<T>): Promise<T> {
  // Not available in every environment (e.g. old browsers, jsdom in tests): fall back to the
  // per-tab guarantee alone.
  if (typeof navigator !== 'undefined' && 'locks' in navigator && navigator.locks) {
    // The DOM typings say Promise<Promise<T>> for an async callback; at runtime the promise
    // returned by request() resolves with the callback's result, so it is a Promise<T>.
    return navigator.locks.request(REFRESH_LOCK, task) as unknown as Promise<T>
  }
  return task()
}
