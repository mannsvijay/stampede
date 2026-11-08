import createClient from 'openapi-fetch'

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

let accessToken: string | null = null
let refreshInFlight: Promise<string> | null = null

export function getAccessToken(): string | null {
  return accessToken
}

export function setAccessToken(token: string | null): void {
  accessToken = token
}

export async function login(email: string, password: string): Promise<void> {
  const { data, error, response } = await authClient.POST('/auth/login', { body: { email, password } })
  if (error !== undefined || data === undefined) {
    throw ApiError.fromBody(response.status, error)
  }
  accessToken = data.accessToken
}

export async function logout(): Promise<void> {
  try {
    await authClient.POST('/auth/logout', { headers: CLIENT_HEADER })
  } finally {
    accessToken = null
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
  let result
  try {
    result = await authClient.POST('/auth/refresh', { headers: CLIENT_HEADER })
  } catch (cause) {
    throw ApiError.network(cause)
  }
  const { data, error, response } = result
  if (error !== undefined || data === undefined) {
    accessToken = null
    throw ApiError.fromBody(response.status, error)
  }
  accessToken = data.accessToken
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
