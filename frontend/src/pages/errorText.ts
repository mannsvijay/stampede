import { ApiError } from '../api/problem'

/** Terse, factual error text (PRD 7). Branches on the stable code, never on the message. */
export function errorText(error: unknown): string {
  if (!(error instanceof ApiError)) {
    return 'Something went wrong.'
  }
  switch (error.code) {
    case 'EVENT_NOT_FOUND':
    case 'VALIDATION_FAILED':
      return 'No such drop.'
    case 'NETWORK_ERROR':
      return 'The server could not be reached.'
    case 'INTERNAL_ERROR':
      return error.errorId ? `Server error. Reference ${error.errorId}.` : 'Server error.'
    default:
      return error.detail ?? 'Something went wrong.'
  }
}
