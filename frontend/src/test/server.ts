import { HttpResponse } from 'msw'
import { setupServer } from 'msw/node'

import type { Problem } from '../api/problem'

/** Starts with no handlers: each test declares the API responses it needs with server.use(...). */
export const server = setupServer()

/** A problem+json response, typed against the spec so test bodies can't drift from it. */
export function problem(status: number, code: Problem['code'], detail = 'detail', extra: Partial<Problem> = {}) {
  const body: Problem = { status, code, title: code, detail, ...extra }
  return HttpResponse.json(body, { status, headers: { 'Content-Type': 'application/problem+json' } })
}
