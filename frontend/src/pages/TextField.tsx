import type { Ref } from 'react'

import styles from './AuthForm.module.css'

interface TextFieldProps {
  id: string
  label: string
  type: 'email' | 'password'
  autoComplete: 'email' | 'current-password' | 'new-password'
  value: string
  onChange: (value: string) => void
  /** Shown before anything is typed (e.g. the password rules). */
  hint?: string
  error?: string
  inputRef?: Ref<HTMLInputElement>
}

/**
 * A labelled input. The hint and the error are linked with aria-describedby, so a screen reader
 * reads them with the label when the field gets focus; aria-invalid marks the field as wrong.
 */
export function TextField({ id, label, type, autoComplete, value, onChange, hint, error, inputRef }: TextFieldProps) {
  const hintId = `${id}-hint`
  const errorId = `${id}-error`
  const describedBy = [hint ? hintId : null, error ? errorId : null].filter(Boolean).join(' ')

  return (
    <div className={styles.field}>
      <label htmlFor={id} className="label">
        {label}
      </label>
      {hint && (
        <p id={hintId} className={styles.hint}>
          {hint}
        </p>
      )}
      <input
        id={id}
        name={id}
        type={type}
        autoComplete={autoComplete}
        value={value}
        onChange={(event) => onChange(event.target.value)}
        required
        aria-invalid={error ? true : undefined}
        aria-describedby={describedBy || undefined}
        ref={inputRef}
        className={styles.input}
      />
      {error && (
        <p id={errorId} className={styles.fieldError}>
          {error}
        </p>
      )}
    </div>
  )
}
