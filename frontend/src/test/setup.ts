import '@testing-library/jest-dom/vitest'

import { cleanup } from '@testing-library/react'
import { afterAll, afterEach, beforeAll } from 'vitest'

import { setAccessToken } from '../api/auth'
import { server } from './server'

// MSW (Mock Service Worker) intercepts fetch at the network level, so tests run the real API
// client, auth logic and URLs against fake responses.
//
// A request no handler matches must fail the test. MSW's 'error' strategy only logs and makes that
// fetch fail, which the app may handle gracefully (e.g. "The server could not be reached."), so
// the test could still pass. We record unhandled requests and fail the test explicitly.
const unhandled: string[] = []

beforeAll(() => {
  server.listen({ onUnhandledFrame: 'error' })
  server.events.on('request:unhandled', ({ request }) => {
    unhandled.push(`${request.method} ${new URL(request.url).pathname}`)
  })
})

afterEach(() => {
  server.resetHandlers()
  cleanup()
  setAccessToken(null)
  const requests = unhandled.splice(0)
  if (requests.length > 0) {
    throw new Error(`Unexpected API request(s) with no MSW handler: ${requests.join(', ')}`)
  }
})

afterAll(() => server.close())
