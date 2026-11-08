import type { components } from './schema'

export type Problem = components['schemas']['Problem']
type FieldError = components['schemas']['FieldError']

/** Every code the API can return (from the spec), plus one for "no usable response at all". */
export type ErrorCode = Problem['code'] | 'NETWORK_ERROR'

/**
 * An API error, read from the RFC 7807 problem+json body. Code that handles errors switches on
 * {@link code} (a typed union), never on the human-readable {@link detail}.
 */
export class ApiError extends Error {
  readonly status: number
  readonly code: ErrorCode
  readonly detail: string | undefined
  readonly errors: FieldError[]
  readonly errorId: string | undefined

  constructor(status: number, code: ErrorCode, detail?: string, errors: FieldError[] = [], errorId?: string) {
    super(detail ?? code)
    this.name = 'ApiError'
    this.status = status
    this.code = code
    this.detail = detail
    this.errors = errors
    this.errorId = errorId
  }

  /** From an HTTP status and a parsed response body that should be a problem. */
  static fromBody(status: number, body: unknown): ApiError {
    if (isProblem(body)) {
      return new ApiError(status, body.code, body.detail, body.errors ?? [], body.errorId)
    }
    // Not a problem body (e.g. a proxy's HTML error page): keep the status, use a generic code.
    return new ApiError(status, status >= 500 ? 'INTERNAL_ERROR' : 'REQUEST_FAILED', `HTTP ${status}`)
  }

  static async fromResponse(response: Response): Promise<ApiError> {
    const body: unknown = await response.json().catch(() => null)
    return ApiError.fromBody(response.status, body)
  }

  static network(cause: unknown): ApiError {
    const error = new ApiError(0, 'NETWORK_ERROR', 'The server could not be reached.')
    error.cause = cause
    return error
  }
}

function isProblem(body: unknown): body is Problem {
  return typeof body === 'object' && body !== null && typeof (body as { code?: unknown }).code === 'string'
}
