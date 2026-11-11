import type { RouteObject } from 'react-router'

import { BoardPage } from './pages/BoardPage'
import { EventPage } from './pages/EventPage'
import { Layout } from './pages/Layout'
import { NotFoundPage } from './pages/NotFoundPage'

/**
 * App pages. They deliberately don't share prefixes with the API (/auth, /events, /holds, /orders,
 * /me, /tickets, /actuator, /v3), which the Vite dev proxy forwards to the backend: reloading
 * /drops/:id must load the app, not the API's JSON (D-011). Hence /wallet, not /tickets or /me.
 */
export const routes: RouteObject[] = [
  {
    element: <Layout />,
    children: [
      { path: '/', element: <BoardPage /> },
      { path: '/drops/:id', element: <EventPage /> },
      { path: '*', element: <NotFoundPage /> },
    ],
  },
]
