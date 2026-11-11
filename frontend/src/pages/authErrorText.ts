import { ApiError } from '../api/problem'

export type AuthField = 'email' | 'password'

/** What the login and register forms show: one message for the whole form, and one per field. */
export interface AuthFormErrors {
  form: string
  fields: Partial<Record<AuthField, string>>
}

const LABELS: Record<AuthField, string> = { email: 'Email', password: 'Password' }

/**
 * Plain-words error text for the auth forms, chosen by the stable `code` (never by `detail`).
 * Separate from errorText.ts, where VALIDATION_FAILED means a malformed event id ("No such drop.").
 */
export function authErrors(error: unknown): AuthFormErrors {
  if (!(error instanceof ApiError)) {
    return { form: 'Something went wrong. Try again.', fields: {} }
  }
  switch (error.code) {
    case 'INVALID_CREDENTIALS':
      // The server gives the same answer for an unknown email and a wrong password (D-008).
      return { form: 'Email or password is wrong.', fields: {} }
    case 'EMAIL_TAKEN':
      return withSummary({ email: 'An account with this email already exists.' }, [])
    case 'VALIDATION_FAILED':
      return validationErrors(error)
    case 'NETWORK_ERROR':
      return { form: 'The server could not be reached. Try again.', fields: {} }
    case 'INTERNAL_ERROR':
      return { form: error.errorId ? `Server error. Reference ${error.errorId}.` : 'Server error.', fields: {} }
    default:
      return { form: 'Something went wrong. Try again.', fields: {} }
  }
}

/**
 * Each field error goes next to its field. The wording comes from the server, which owns the rules
 * (e.g. "must be at least 12 characters"), so the form never repeats them in a second place.
 */
function validationErrors(error: ApiError): AuthFormErrors {
  const fields: Partial<Record<AuthField, string>> = {}
  const other: string[] = []
  for (const { field, message } of error.errors) {
    if (isAuthField(field)) {
      const sentence = `${LABELS[field]} ${message}.`
      fields[field] = fields[field] ? `${fields[field]} ${sentence}` : sentence
    } else {
      other.push(`${field} ${message}.`)
    }
  }
  if (Object.keys(fields).length === 0 && other.length === 0) {
    return { form: 'Check your details and try again.', fields }
  }
  return withSummary(fields, other)
}

/**
 * The form-level message when the problems are on fields: it is what a screen reader announces
 * (the form's alert region), and it names the fields to look at.
 */
function withSummary(fields: Partial<Record<AuthField, string>>, other: string[]): AuthFormErrors {
  const names = (Object.keys(fields) as AuthField[]).map((field) => LABELS[field].toLowerCase())
  const summary =
    names.length === 0 ? '' : `Check the ${names.join(' and ')} field${names.length > 1 ? 's' : ''}.`
  return { form: [summary, ...other].filter(Boolean).join(' '), fields }
}

function isAuthField(field: string): field is AuthField {
  return field === 'email' || field === 'password'
}
