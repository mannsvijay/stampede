import { http, HttpResponse } from 'msw'
import { setupServer } from 'msw/node'

import type { Problem } from '../api/problem'

/**
 * Starts with one handler: the start-up session restore (POST /auth/refresh) answers "no session",
 * as it does for a visitor who never logged in. Every app render sends it, so without a default
 * every page test would have to declare it. Each test declares everything else with server.use(...),
 * which also overrides this default; resetHandlers() goes back to it after each test.
 */
export const server = setupServer(http.post('/auth/refresh', () => problem(401, 'INVALID_REFRESH_TOKEN')))

/** A problem+json response, typed against the spec so test bodies can't drift from it. */
export function problem(status: number, code: Problem['code'], detail = 'detail', extra: Partial<Problem> = {}) {
  const body: Problem = { status, code, title: code, detail, ...extra }
  return HttpResponse.json(body, { status, headers: { 'Content-Type': 'application/problem+json' } })
}
