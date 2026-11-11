import { fireEvent, screen, waitFor } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'

import { getAccessToken } from '../api/auth'
import { renderAt } from '../test/render'
import { problem, server } from '../test/server'

/** The alert region is always in the page (empty until there is an error), so wait for its text. */
async function expectAlert(text: string) {
  await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent(text))
}

async function fillAndSubmit(email: string, password: string) {
  fireEvent.change(await screen.findByLabelText('Email'), { target: { value: email } })
  fireEvent.change(screen.getByLabelText('Password'), { target: { value: password } })
  fireEvent.click(screen.getByRole('button', { name: 'Log in' }))
}

describe('LoginPage', () => {
  it('logs in, keeps the token in memory only, and goes to next', async () => {
    let body: unknown = null
    server.use(
      http.post('/auth/login', async ({ request }) => {
        body = await request.json()
        return HttpResponse.json({ accessToken: 'token-1', tokenType: 'Bearer', expiresIn: 900 })
      }),
    )
    const { router } = renderAt('/login?next=%2Fsomewhere%3Fx%3D1')

    await fillAndSubmit('fan@test.local', 'correct-horse-battery')

    expect(await screen.findByRole('button', { name: 'Log out' })).toBeInTheDocument()
    expect(router.state.location.pathname + router.state.location.search).toBe('/somewhere?x=1')
    expect(body).toEqual({ email: 'fan@test.local', password: 'correct-horse-battery' })
    expect(getAccessToken()).toBe('token-1')
    expect(window.localStorage.length).toBe(0)
    expect(window.sessionStorage.length).toBe(0)
  })

  it('INVALID_CREDENTIALS: says so in the alert region and stays logged out', async () => {
    server.use(http.post('/auth/login', () => problem(401, 'INVALID_CREDENTIALS')))
    const { router } = renderAt('/login')

    await fillAndSubmit('fan@test.local', 'wrong-password-123')

    await expectAlert('Email or password is wrong.')
    expect(router.state.location.pathname).toBe('/login')
    expect(getAccessToken()).toBeNull()
    expect(screen.getByRole('button', { name: 'Log in' })).toBeEnabled()
  })

  it('VALIDATION_FAILED: the message is next to its field, which is marked invalid and focused', async () => {
    server.use(
      http.post('/auth/login', () =>
        problem(400, 'VALIDATION_FAILED', 'The request body is invalid.', {
          errors: [{ field: 'email', message: 'must not be blank' }],
        }),
      ),
    )
    renderAt('/login')

    await fillAndSubmit('', 'whatever-password')

    await expectAlert('Check the email field.')
    const email = screen.getByLabelText('Email')
    expect(email).toHaveAttribute('aria-invalid', 'true')
    expect(email).toHaveAccessibleDescription('Email must not be blank.')
    await waitFor(() => expect(email).toHaveFocus())
    expect(screen.getByLabelText('Password')).not.toHaveAttribute('aria-invalid')
  })

  it('NETWORK_ERROR: says the server could not be reached', async () => {
    server.use(http.post('/auth/login', () => HttpResponse.error()))
    renderAt('/login')

    await fillAndSubmit('fan@test.local', 'correct-horse-battery')

    await expectAlert('The server could not be reached. Try again.')
  })

  it('has real labels and the autocomplete hints password managers use', async () => {
    renderAt('/login')

    expect(await screen.findByLabelText('Email')).toHaveAttribute('autocomplete', 'email')
    expect(screen.getByLabelText('Email')).toHaveAttribute('type', 'email')
    expect(screen.getByLabelText('Password')).toHaveAttribute('autocomplete', 'current-password')
    expect(screen.getByLabelText('Password')).toHaveAttribute('type', 'password')
  })
})
