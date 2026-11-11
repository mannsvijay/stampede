import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render } from '@testing-library/react'
import { createMemoryRouter, type RouteObject, RouterProvider } from 'react-router'

import { routes } from '../routes'

/**
 * Renders the real app routes (or `routeList`) at `path`, with a fresh query cache and no retries.
 * Returns the router too, so tests can check where navigation ended up.
 */
export function renderAt(path: string, routeList: RouteObject[] = routes) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const router = createMemoryRouter(routeList, { initialEntries: [path] })
  const result = render(
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
  return { ...result, router, queryClient }
}
