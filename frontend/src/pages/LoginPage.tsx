import { Link, Navigate, useLocation, useSearchParams } from 'react-router'

import { login } from '../api/auth'
import { safeNext } from '../auth/safeNext'
import { useSession } from '../auth/useSession'
import styles from './AuthForm.module.css'
import { TextField } from './TextField'
import { useAuthForm } from './useAuthForm'

/** Set by the register page when the account was created but the automatic login failed. */
export interface LoginNotice {
  notice?: string
}

export function LoginPage() {
  const status = useSession()
  const [params] = useSearchParams()
  const next = safeNext(params.get('next'))
  const notice = (useLocation().state as LoginNotice | null)?.notice
  const form = useAuthForm(login)

  if (status === 'restoring') {
    return null // a moment: if the session comes back, this page redirects instead
  }
  if (status === 'loggedIn') {
    return <Navigate to={next} replace />
  }

  return (
    <section className={styles.page}>
      <h1 className={styles.title}>Log in</h1>
      {notice && (
        <p role="status" className={styles.notice}>
          {notice}
        </p>
      )}
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
        <TextField
          id="password"
          label="Password"
          type="password"
          autoComplete="current-password"
          value={form.password}
          onChange={form.setPassword}
          error={form.errors?.fields.password}
          inputRef={form.passwordRef}
        />
        <p role="alert" className={styles.formError}>
          {form.errors?.form}
        </p>
        <button type="submit" disabled={form.submitting} className={styles.submit}>
          {form.submitting ? 'Logging in…' : 'Log in'}
        </button>
      </form>
      <p className={styles.switch}>
        No account? <Link to={`/register?next=${encodeURIComponent(next)}`}>Create one</Link>
      </p>
    </section>
  )
}
