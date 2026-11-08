import { errorText } from './errorText'

export function ErrorMessage({ error }: { error: unknown }) {
  return <p role="alert">{errorText(error)}</p>
}
