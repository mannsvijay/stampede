import { QueryClient } from '@tanstack/react-query'

import { shouldRetry } from './api/client'

export function createQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: {
        retry: shouldRetry,
        // Data counts as fresh for 30 s. After that, the board re-fetches when the tab regains
        // focus, which also refreshes the clock offset.
        staleTime: 30_000,
      },
    },
  })
}
