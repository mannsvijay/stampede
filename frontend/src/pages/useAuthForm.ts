import { type FormEvent, useEffect, useRef, useState } from 'react'

import { type AuthFormErrors, authErrors } from './authErrorText'

/**
 * State shared by the login and register forms: the two fields, the request in flight, and the
 * errors. `submit` throws on failure; on success the page redirects by itself (the session status
 * becomes `loggedIn`).
 */
export function useAuthForm(submit: (email: string, password: string) => Promise<void>) {
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [errors, setErrors] = useState<AuthFormErrors | null>(null)
  const emailRef = useRef<HTMLInputElement>(null)
  const passwordRef = useRef<HTMLInputElement>(null)

  // After a failed submit, move focus to the first field that is wrong, so keyboard and screen
  // reader users land on it and hear its error (it is linked with aria-describedby).
  useEffect(() => {
    if (errors?.fields.email) {
      emailRef.current?.focus()
    } else if (errors?.fields.password) {
      passwordRef.current?.focus()
    }
  }, [errors])

  async function onSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (submitting) {
      return
    }
    // Clear first: the alert region's text then changes even when the same error repeats, so a
    // screen reader announces it again.
    setErrors(null)
    setSubmitting(true)
    try {
      await submit(email, password)
    } catch (error) {
      setErrors(authErrors(error))
    } finally {
      setSubmitting(false)
    }
  }

  return { email, setEmail, password, setPassword, submitting, errors, emailRef, passwordRef, onSubmit }
}
