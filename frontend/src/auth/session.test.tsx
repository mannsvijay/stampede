import { useQuery } from '@tanstack/react-query'
import { fireEvent, screen, waitFor } from '@testing-library/react'
import { delay, http, HttpResponse } from 'msw'
import type { RouteObject } from 'react-router'
import { describe, expect, it } from 'vitest'

import { getAccessToken, getSessionStatus, restoreSession } from '../api/auth'
import { api } from '../api/client'
import { ApiError } from '../api/problem'
import { saveHold } from '../booking/holdStore'
import { loadLastOrder, saveLastOrder } from '../booking/lastOrderStore'
import { Layout } from '../pages/Layout'
import { NotFoundPage } from '../pages/NotFoundPage'
import { appPages } from '../routes'
import { renderAt } from '../test/render'
import { problem, server } from '../test/server'
import { RequireAuth } from './RequireAuth'

/** A page that needs a user and, like the real ones will, loads two things at once. */
function SecretPage() {
  return (
    <>
      <h1>Secret page</h1>
      <TicketCount label="first" />
      <TicketCount label="second" />
    </>
  )
}

function TicketCount({ label }: { label: string }) {
  const query = useQuery({
    queryKey: ['me', 'tickets', label],
    queryFn: async () => {
      const { data, error, response } = await api.GET('/me/tickets')
      if (error !== undefined || data === undefined) {
        throw ApiError.fromBody(response.status, error)
      }
      return data
    },
  })
  return query.data ? <p>{`${label}: ${query.data.items.length} tickets`}</p> : null
}

/** The real app routes plus a protected test page (M1.8c adds the real ones). */
const testRoutes: RouteObject[] = [
  {
    element: <Layout />,
    children: [
      ...appPages,
      { element: <RequireAuth />, children: [{ path: '/secret', element: <SecretPage /> }] },
      { path: '*', element: <NotFoundPage /> },
    ],
  },
]

/** POST /auth/refresh hands out these tokens in order, then answers INVALID_REFRESH_TOKEN. */
function serveRefresh(...tokens: string[]) {
  const calls = { count: 0 }
  server.use(
    http.post('/auth/refresh', async () => {
      calls.count++
      await delay(20)
      const token = tokens.shift()
      return token
        ? HttpResponse.json({ accessToken: token, tokenType: 'Bearer', expiresIn: 900 })
        : problem(401, 'INVALID_REFRESH_TOKEN')
    }),
  )
  return calls
}

/** GET /me/tickets accepts only `validToken`. */
function serveTickets(validToken: string) {
  const calls = { unauthorized: 0 }
  server.use(
    http.get('/me/tickets', ({ request }) => {
      if (request.headers.get('Authorization') === `Bearer ${validToken}`) {
        return HttpResponse.json({ items: [] })
      }
      calls.unauthorized++
      return problem(401, 'UNAUTHENTICATED')
    }),
  )
  return calls
}

function serveLogin(token: string) {
  server.use(
    http.post('/auth/login', () => HttpResponse.json({ accessToken: token, tokenType: 'Bearer', expiresIn: 900 })),
  )
}

/** The board at /, with no drops. Await `loaded()` before a test ends on /, so no request outlives it. */
function serveEmptyBoard() {
  let requests = 0
  server.use(
    http.get('/events', () => {
      requests++
      return HttpResponse.json({ items: [], serverTime: new Date().toISOString() })
    }),
  )
  return { loaded: () => waitFor(() => expect(requests).toBeGreaterThan(0)) }
}

async function logIn() {
  fireEvent.change(await screen.findByLabelText('Email'), { target: { value: 'fan@test.local' } })
  fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'correct-horse-battery' } })
  fireEvent.click(screen.getByRole('button', { name: 'Log in' }))
}

/** A hold as the seats page stores it (sessionStorage), still valid for 3 minutes. */
function storeHold() {
  saveHold({
    holdId: 'hold-1',
    eventId: 'event-1',
    seatIds: ['seat-1'],
    expiresAt: new Date(Date.now() + 3 * 60_000).toISOString(),
    clockOffsetMs: 0,
  })
}

function storeLastOrder() {
  saveLastOrder({ orderId: 'order-1', holdId: 'hold-0', eventId: 'event-1', itemCount: 2 })
}

const where = (router: ReturnType<typeof renderAt>['router']) =>
  router.state.location.pathname + router.state.location.search

describe('session restore on page load', () => {
  it('restores the session with one refresh call (a reload keeps you logged in)', async () => {
    const refresh = serveRefresh('restored-token')
    serveTickets('restored-token')

    renderAt('/secret', testRoutes)

    expect(await screen.findByText('first: 0 tickets')).toBeInTheDocument()
    expect(screen.getByText('second: 0 tickets')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Log out' })).toBeInTheDocument()
    expect(refresh.count).toBe(1)
    expect(getAccessToken()).toBe('restored-token')
  })

  it('calling restoreSession again (StrictMode runs effects twice) sends no second refresh', async () => {
    const refresh = serveRefresh('restored-token')

    await Promise.all([restoreSession(), restoreSession()])
    await restoreSession()

    expect(refresh.count).toBe(1)
    expect(getSessionStatus()).toBe('loggedIn')
  })

  it('INVALID_REFRESH_TOKEN just means logged out: no error is shown', async () => {
    // The default handler answers 401 INVALID_REFRESH_TOKEN.
    const board = serveEmptyBoard()
    renderAt('/', testRoutes)

    expect(await screen.findByRole('link', { name: 'Log in' })).toBeInTheDocument()
    await board.loaded()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    expect(getSessionStatus()).toBe('loggedOut')
  })

  it('a protected page waits for the restore instead of redirecting to /login', async () => {
    serveRefresh('restored-token')
    serveTickets('restored-token')
    const { router } = renderAt('/secret', testRoutes)

    expect(screen.getByRole('status')).toHaveTextContent('Checking your session…')
    expect(await screen.findByRole('heading', { name: 'Secret page' })).toBeInTheDocument()
    expect(where(router)).toBe('/secret')
    expect(await screen.findByText('second: 0 tickets')).toBeInTheDocument()
  })
})

describe('RequireAuth and ?next=', () => {
  it('sends a logged-out user to /login?next=..., then back to that page after logging in', async () => {
    serveLogin('token-1')
    serveTickets('token-1')
    const { router } = renderAt('/secret?tab=2', testRoutes)

    await waitFor(() => expect(where(router)).toBe('/login?next=%2Fsecret%3Ftab%3D2'))
    await logIn()

    expect(await screen.findByRole('heading', { name: 'Secret page' })).toBeInTheDocument()
    expect(where(router)).toBe('/secret?tab=2')
    // Let the page's own requests finish, so none is still in flight when this test's handlers go.
    expect(await screen.findByText('second: 0 tickets')).toBeInTheDocument()
  })

  it.each(['https://evil.example/phish', '//evil.example', '/\\evil.example'])(
    'ignores an unsafe next (%s) and goes to / instead',
    async (next) => {
      serveLogin('token-1')
      const board = serveEmptyBoard()
      const { router } = renderAt(`/login?next=${encodeURIComponent(next)}`, testRoutes)

      await logIn()

      await waitFor(() => expect(where(router)).toBe('/'))
      await board.loaded()
    },
  )

  it('when the session dies mid-visit (refresh rejected), the page sends the user to log in', async () => {
    serveRefresh('token-1') // restore works; the next refresh gets INVALID_REFRESH_TOKEN
    serveTickets('a-token-nobody-has') // every request is a 401
    const { router } = renderAt('/secret', testRoutes)

    await waitFor(() => expect(where(router)).toBe('/login?next=%2Fsecret'))
    expect(getAccessToken()).toBeNull()
  })
})

describe('logout', () => {
  it('calls POST /auth/logout, clears the token, cached data, the stored hold and last order, and shows Log in', async () => {
    storeHold()
    storeLastOrder()
    serveRefresh('token-1')
    serveTickets('token-1')
    const board = serveEmptyBoard()
    let logoutHeader: string | null = null
    server.use(
      http.post('/auth/logout', ({ request }) => {
        logoutHeader = request.headers.get('X-Requested-With')
        return new HttpResponse(null, { status: 204 })
      }),
    )
    const { router, queryClient } = renderAt('/secret', testRoutes)
    await screen.findByText('first: 0 tickets')

    fireEvent.click(screen.getByRole('button', { name: 'Log out' }))

    expect(await screen.findByRole('link', { name: 'Log in' })).toBeInTheDocument()
    expect(logoutHeader).toBe('stampede-web')
    expect(getAccessToken()).toBeNull()
    expect(queryClient.getQueryData(['me', 'tickets', 'first'])).toBeUndefined()
    expect(sessionStorage.length).toBe(0) // the hold and the last order, on a possibly shared computer
    expect(loadLastOrder()).toBeNull()
    expect(where(router)).toBe('/')
    await board.loaded()
  })

  it('if the server cannot be reached, still forgets the token, the stored hold and last order, and warns', async () => {
    storeHold()
    storeLastOrder()
    serveRefresh('token-1')
    serveTickets('token-1')
    const board = serveEmptyBoard()
    server.use(http.post('/auth/logout', () => HttpResponse.error()))
    renderAt('/secret', testRoutes)
    await screen.findByText('first: 0 tickets')

    fireEvent.click(screen.getByRole('button', { name: 'Log out' }))

    expect(await screen.findByText(/You may still be logged in after a reload/)).toBeInTheDocument()
    expect(getAccessToken()).toBeNull()
    expect(sessionStorage.length).toBe(0)
    expect(loadLastOrder()).toBeNull()
    await board.loaded()
  })
})

describe('the stored hold and who is logged in', () => {
  it('a new login drops a hold and last order stored before it (this tab may now be someone else)', async () => {
    storeHold()
    storeLastOrder()
    serveLogin('token-1')
    const board = serveEmptyBoard()
    renderAt('/login', testRoutes)

    await logIn()

    expect(await screen.findByRole('button', { name: 'Log out' })).toBeInTheDocument()
    expect(sessionStorage.length).toBe(0)
    await board.loaded()
  })

  it('a reload (session restored by refresh) keeps the stored hold: same person', async () => {
    storeHold()
    serveRefresh('token-1')
    const board = serveEmptyBoard()
    renderAt('/', testRoutes)

    expect(await screen.findByRole('button', { name: 'Log out' })).toBeInTheDocument()
    expect(sessionStorage.length).toBe(1)
    await board.loaded()
  })

  it('a failed login attempt keeps it (a mistyped password is no reason to lose seats)', async () => {
    storeHold()
    server.use(http.post('/auth/login', () => problem(401, 'INVALID_CREDENTIALS')))
    renderAt('/login', testRoutes)

    await logIn()

    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('Email or password is wrong.'))
    expect(sessionStorage.length).toBe(1)
  })
})

describe('expired access token', () => {
  it('two requests that get 401 at the same time cause a single refresh, then both succeed', async () => {
    const refresh = serveRefresh('token-1', 'token-2') // restore gets token-1, which has expired
    const tickets = serveTickets('token-2')

    renderAt('/secret', testRoutes)

    expect(await screen.findByText('first: 0 tickets')).toBeInTheDocument()
    expect(await screen.findByText('second: 0 tickets')).toBeInTheDocument()
    expect(tickets.unauthorized).toBe(2) // both really got a 401 with token-1
    expect(refresh.count).toBe(2) // the start-up restore, then ONE shared refresh for both
    expect(getAccessToken()).toBe('token-2')
  })
})
