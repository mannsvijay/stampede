import { delay, http, HttpResponse } from 'msw'
import { afterEach, describe, expect, it } from 'vitest'

import { server, problem } from '../test/server'
import { getAccessToken, login, logout, REFRESH_LOCK, refreshAccessToken, setAccessToken } from './auth'
import { authFetch } from './http'
import { ApiError } from './problem'

const PROTECTED = '/me/thing'
const url = (path: string) => new URL(path, window.location.origin).toString()

/**
 * A protected endpoint that only accepts `validToken`, and a refresh endpoint that issues it after
 * a short delay (so concurrent 401s really overlap with the refresh). Records what happened.
 */
function fakeApi(validToken = 'new-token') {
  const calls = { refresh: 0, unauthorized: 0, refreshHeaders: [] as (string | null)[] }
  server.use(
    http.post('/auth/refresh', async ({ request }) => {
      calls.refresh++
      calls.refreshHeaders.push(request.headers.get('X-Requested-With'))
      await delay(30)
      return HttpResponse.json({ accessToken: validToken, tokenType: 'Bearer', expiresIn: 900 })
    }),
    http.get(PROTECTED, ({ request }) => {
      if (request.headers.get('Authorization') === `Bearer ${validToken}`) {
        return HttpResponse.json({ ok: true })
      }
      calls.unauthorized++
      return problem(401, 'UNAUTHENTICATED')
    }),
  )
  return calls
}

describe('authFetch: single in-flight refresh', () => {
  it('three requests that all get 401 at once trigger exactly one refresh, then all succeed', async () => {
    const calls = fakeApi()
    setAccessToken('expired-token')

    const responses = await Promise.all([1, 2, 3].map(() => authFetch(new Request(url(PROTECTED)))))

    expect(calls.unauthorized).toBe(3) // all three really got a 401 first
    expect(calls.refresh).toBe(1)
    expect(responses.map((r) => r.status)).toEqual([200, 200, 200])
    expect(getAccessToken()).toBe('new-token')
  })

  it('a request whose 401 arrives after another request already refreshed retries without refreshing', async () => {
    const calls = fakeApi()
    let slowUnauthorized = 0
    server.use(
      // Requests marked slow take 150 ms to answer 401: longer than the whole refresh (30 ms).
      http.get('/me/slow', async ({ request }) => {
        if (request.headers.get('Authorization') === 'Bearer new-token') {
          return HttpResponse.json({ ok: true })
        }
        await delay(150)
        slowUnauthorized++
        return problem(401, 'UNAUTHENTICATED')
      }),
    )
    setAccessToken('expired-token')

    // Both are sent with the expired token. The fast one gets its 401 and refreshes; the slow
    // one's 401 only arrives after the new token is already in memory.
    const [fast, slow] = await Promise.all([
      authFetch(new Request(url(PROTECTED))),
      authFetch(new Request(url('/me/slow'))),
    ])

    expect(slowUnauthorized).toBe(1)
    expect(calls.refresh).toBe(1)
    expect([fast.status, slow.status]).toEqual([200, 200])
  })

  it('retries only once: if the retry is also 401, that response is returned and no second refresh runs', async () => {
    const calls = fakeApi('token-the-server-will-never-accept')
    server.use(
      http.post('/auth/refresh', () => {
        calls.refresh++
        return HttpResponse.json({ accessToken: 'still-rejected', tokenType: 'Bearer', expiresIn: 900 })
      }),
    )
    setAccessToken('expired-token')

    const response = await authFetch(new Request(url(PROTECTED)))

    expect(response.status).toBe(401)
    expect(calls.refresh).toBe(1)
    expect(calls.unauthorized).toBe(2)
  })

  it('when the refresh fails, every waiting request rejects with its code and the token is cleared', async () => {
    let refreshCalls = 0
    server.use(
      http.post('/auth/refresh', async () => {
        refreshCalls++
        await delay(30)
        return problem(401, 'INVALID_REFRESH_TOKEN')
      }),
      http.get(PROTECTED, () => problem(401, 'UNAUTHENTICATED')),
    )
    setAccessToken('expired-token')

    const results = await Promise.allSettled([1, 2, 3].map(() => authFetch(new Request(url(PROTECTED)))))

    expect(refreshCalls).toBe(1)
    for (const result of results) {
      expect(result.status).toBe('rejected')
      const reason = (result as PromiseRejectedResult).reason
      expect(reason).toBeInstanceOf(ApiError)
      expect((reason as ApiError).code).toBe('INVALID_REFRESH_TOKEN')
    }
    expect(getAccessToken()).toBeNull()
  })

  it('does not refresh on a 401 from /auth itself (e.g. wrong password)', async () => {
    let refreshCalls = 0
    server.use(
      http.post('/auth/login', () => problem(401, 'INVALID_CREDENTIALS')),
      http.post('/auth/refresh', () => {
        refreshCalls++
        return problem(401, 'INVALID_REFRESH_TOKEN')
      }),
    )

    const response = await authFetch(new Request(url('/auth/login'), { method: 'POST' }))
    await expect(login('a@b.c', 'wrong-password-123')).rejects.toMatchObject({ code: 'INVALID_CREDENTIALS' })

    expect(response.status).toBe(401)
    expect(refreshCalls).toBe(0)
  })

  it('a network failure becomes NETWORK_ERROR', async () => {
    server.use(http.get(PROTECTED, () => HttpResponse.error()))

    await expect(authFetch(new Request(url(PROTECTED)))).rejects.toMatchObject({ code: 'NETWORK_ERROR' })
  })
})

describe('auth calls', () => {
  it('refresh and logout send X-Requested-With (CSRF guard, D-008)', async () => {
    const calls = fakeApi()
    let logoutHeader: string | null = null
    server.use(
      http.post('/auth/logout', ({ request }) => {
        logoutHeader = request.headers.get('X-Requested-With')
        return new HttpResponse(null, { status: 204 })
      }),
    )

    await refreshAccessToken()
    await logout()

    expect(calls.refreshHeaders).toEqual(['stampede-web'])
    expect(logoutHeader).toBe('stampede-web')
    expect(getAccessToken()).toBeNull()
  })

  it('login keeps the access token in memory and sends it on later requests', async () => {
    fakeApi('logged-in-token')
    server.use(
      http.post('/auth/login', () =>
        HttpResponse.json({ accessToken: 'logged-in-token', tokenType: 'Bearer', expiresIn: 900 }),
      ),
    )

    await login('user@test.local', 'correct-horse-battery')
    const response = await authFetch(new Request(url(PROTECTED)))

    expect(getAccessToken()).toBe('logged-in-token')
    expect(response.status).toBe(200)
    expect(window.localStorage.length).toBe(0)
    expect(window.sessionStorage.length).toBe(0)
  })
})

describe('cross-tab lock (Web Locks API)', () => {
  afterEach(() => {
    Reflect.deleteProperty(navigator, 'locks')
  })

  it('runs the refresh inside the shared browser lock when the API exists', async () => {
    fakeApi()
    const lockNames: string[] = []
    Object.defineProperty(navigator, 'locks', {
      configurable: true,
      value: {
        request: (name: string, callback: () => Promise<unknown>) => {
          lockNames.push(name)
          return callback()
        },
      },
    })

    await expect(refreshAccessToken()).resolves.toBe('new-token')
    expect(lockNames).toEqual([REFRESH_LOCK])
  })

  it('still refreshes when the API is missing (jsdom, older browsers)', async () => {
    fakeApi()
    expect('locks' in navigator).toBe(false)

    await expect(refreshAccessToken()).resolves.toBe('new-token')
  })
})
