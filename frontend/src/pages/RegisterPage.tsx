import { Link, Navigate, useNavigate, useSearchParams } from 'react-router'

import { login, register } from '../api/auth'
import { loginPath, safeNext } from '../auth/safeNext'
import { useSession } from '../auth/useSession'
import styles from './AuthForm.module.css'
import type { LoginNotice } from './LoginPage'
import { TextField } from './TextField'
import { useAuthForm } from './useAuthForm'

export function RegisterPage() {
  const status = useSession()
  const [params] = useSearchParams()
  const next = safeNext(params.get('next'))
  const navigate = useNavigate()

  const form = useAuthForm(async (email, password) => {
    await register(email, password) // its errors (EMAIL_TAKEN, VALIDATION_FAILED, ...) show on this form
    try {
      await login(email, password)
    } catch {
      // The account exists now; only the automatic login failed (e.g. a network blip).
      const state: LoginNotice = { notice: 'Account created. Log in to continue.' }
      await navigate(loginPath(next), { state })
    }
  })

  if (status === 'restoring') {
    return null
  }
  if (status === 'loggedIn') {
    return <Navigate to={next} replace />
  }

  return (
    <section className={styles.page}>
      <h1 className={styles.title}>Create account</h1>
      <form onSubmit={form.onSubmit} noValidate className={styles.form}>
        <TextField
          id="email"
          label="Email"
          type="email"
          autoComplete="email"
          value={form.email}
          onChange={form.setEmail}
          error={form.errors?.fields.email}
          inputRef={form.emailRef}
        />
        {/* The rule is shown up front; the server checks it (and the 72-byte bcrypt limit). */}
        <TextField
          id="password"
          label="Password"
          type="password"
          autoComplete="new-password"
          value={form.password}
          onChange={form.setPassword}
          hint="At least 12 characters."
          error={form.errors?.fields.password}
          inputRef={form.passwordRef}
        />
        <p role="alert" className={styles.formError}>
          {form.errors?.form}
        </p>
        <button type="submit" disabled={form.submitting} className={styles.submit}>
          {form.submitting ? 'Creating account…' : 'Create account'}
        </button>
      </form>
      <p className={styles.switch}>
        Already have an account? <Link to={loginPath(next)}>Log in</Link>
      </p>
    </section>
  )
}
