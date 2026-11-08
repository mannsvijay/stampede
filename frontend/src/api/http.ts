import { getAccessToken, refreshAccessToken } from './auth'
import { ApiError } from './problem'

/**
 * The fetch used by the API client: adds the bearer token, and on a 401 refreshes once and retries
 * once (D-011).
 *
 * - Concurrent 401s share a single refresh (see refreshAccessToken).
 * - If the token changed while this request was in flight (another request already refreshed),
 *   it just retries with the new token instead of refreshing again.
 * - The retry happens at most once, so a server that keeps answering 401 can't cause a loop.
 * - /auth/* responses are returned as they are: a 401 from login means wrong credentials, not an
 *   expired token.
 * - If the refresh itself fails, the request rejects with the refresh's error (e.g.
 *   INVALID_REFRESH_TOKEN): the session is over, and the caller should treat the user as logged out.
 */
export async function authFetch(request: Request): Promise<Response> {
  // A request body can only be read once, so keep an unread copy for the retry.
  const retry = request.clone()
  const { response, tokenUsed } = await send(request)

  if (response.status !== 401 || isAuthPath(request.url)) {
    return response
  }

  const current = getAccessToken()
  if (current === null || current === tokenUsed) {
    await refreshAccessToken()
  }
  return (await send(retry)).response
}

async function send(request: Request): Promise<{ response: Response; tokenUsed: string | null }> {
  const token = getAccessToken()
  if (token !== null) {
    request.headers.set('Authorization', `Bearer ${token}`)
  }
  try {
    return { response: await globalThis.fetch(request), tokenUsed: token }
  } catch (cause) {
    throw ApiError.network(cause)
  }
}

function isAuthPath(url: string): boolean {
  return new URL(url).pathname.startsWith('/auth/')
}
