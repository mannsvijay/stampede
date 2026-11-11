import type { RouteObject } from 'react-router'

import { RequireAuth } from './auth/RequireAuth'
import { BoardPage } from './pages/BoardPage'
import { EventPage } from './pages/EventPage'
import { Layout } from './pages/Layout'
import { LoginPage } from './pages/LoginPage'
import { NotFoundPage } from './pages/NotFoundPage'
import { RegisterPage } from './pages/RegisterPage'
import { SeatsPage } from './pages/SeatsPage'
import { WalletPage } from './pages/WalletPage'

/**
 * App pages. They deliberately don't share prefixes with the API (/auth, /events, /holds, /orders,
 * /me, /tickets, /actuator, /v3), which the Vite dev proxy forwards to the backend: reloading
 * /drops/:id must load the app, not the API's JSON (D-011). Hence /wallet, not /tickets or /me.
 *
 * Pages that need a user go under the RequireAuth route: logged-out visitors are sent to /login and back.
 */
export const appPages: RouteObject[] = [
  { path: '/', element: <BoardPage /> },
  { path: '/drops/:id', element: <EventPage /> },
  { path: '/login', element: <LoginPage /> },
  { path: '/register', element: <RegisterPage /> },
  {
    element: <RequireAuth />,
    children: [
      { path: '/drops/:id/seats', element: <SeatsPage /> },
      { path: '/wallet', element: <WalletPage /> },
    ],
  },
]

export const routes: RouteObject[] = [
  {
    element: <Layout />,
    children: [...appPages, { path: '*', element: <NotFoundPage /> }],
  },
]
