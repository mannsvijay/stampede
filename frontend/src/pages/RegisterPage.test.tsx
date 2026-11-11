import { fireEvent, screen, waitFor } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'

import { getAccessToken } from '../api/auth'
import { renderAt } from '../test/render'
import { problem, server } from '../test/server'

const USER = { id: '6f1c2a3b-4d5e-4f60-8a7b-9c0d1e2f3a4b', email: 'new@test.local', role: 'USER' }

/** The alert region is always in the page (empty until there is an error), so wait for its text. */
async function expectAlert(text: string) {
  await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent(text))
}

async function fillAndSubmit(email: string, password: string) {
  fireEvent.change(await screen.findByLabelText('Email'), { target: { value: email } })
  fireEvent.change(screen.getByLabelText('Password'), { target: { value: password } })
  fireEvent.click(screen.getByRole('button', { name: 'Create account' }))
}

describe('RegisterPage', () => {
  it('registers, then logs in with the same credentials and goes to next', async () => {
    const calls: string[] = []
    server.use(
      http.post('/auth/register', () => {
        calls.push('register')
        return HttpResponse.json(USER, { status: 201 })
      }),
      http.post('/auth/login', async ({ request }) => {
        calls.push(`login ${JSON.stringify(await request.json())}`)
        return HttpResponse.json({ accessToken: 'token-new', tokenType: 'Bearer', expiresIn: 900 })
      }),
    )
    const { router } = renderAt('/register?next=%2Fsomewhere')

    await fillAndSubmit('new@test.local', 'a-long-enough-password')

    expect(await screen.findByRole('button', { name: 'Log out' })).toBeInTheDocument()
    expect(calls).toEqual(['register', 'login {"email":"new@test.local","password":"a-long-enough-password"}'])
    expect(router.state.location.pathname).toBe('/somewhere')
    expect(getAccessToken()).toBe('token-new')
  })

  it('shows the password rule before anything is typed, with the right autocomplete hints', async () => {
    renderAt('/register')

    const password = await screen.findByLabelText('Password')
    expect(password).toHaveAccessibleDescription('At least 12 characters.')
    expect(password).toHaveAttribute('autocomplete', 'new-password')
    expect(screen.getByLabelText('Email')).toHaveAttribute('autocomplete', 'email')
  })

  it('EMAIL_TAKEN: the message is on the email field, which gets focus', async () => {
    server.use(http.post('/auth/register', () => problem(409, 'EMAIL_TAKEN')))
    renderAt('/register')

    await fillAndSubmit('taken@test.local', 'a-long-enough-password')

    await expectAlert('Check the email field.')
    const email = screen.getByLabelText('Email')
    expect(email).toHaveAttribute('aria-invalid', 'true')
    expect(email).toHaveAccessibleDescription('An account with this email already exists.')
    await waitFor(() => expect(email).toHaveFocus())
    expect(getAccessToken()).toBeNull()
  })

  it("VALIDATION_FAILED on the password: the server's rule is shown under the hint", async () => {
    server.use(
      http.post('/auth/register', () =>
        problem(400, 'VALIDATION_FAILED', 'The request body is invalid.', {
          errors: [{ field: 'password', message: 'must be at least 12 characters' }],
        }),
      ),
    )
    renderAt('/register')

    await fillAndSubmit('new@test.local', 'short')

    const password = screen.getByLabelText('Password')
    await waitFor(() => expect(password).toHaveAttribute('aria-invalid', 'true'))
    expect(password).toHaveAccessibleDescription('At least 12 characters. Password must be at least 12 characters.')
    await expectAlert('Check the password field.')
    await waitFor(() => expect(password).toHaveFocus())
  })

  it('account created but the automatic login fails: goes to /login with a notice', async () => {
    server.use(
      http.post('/auth/register', () => HttpResponse.json(USER, { status: 201 })),
      http.post('/auth/login', () => HttpResponse.error()),
    )
    const { router } = renderAt('/register?next=%2Fsomewhere')

    await fillAndSubmit('new@test.local', 'a-long-enough-password')

    expect(await screen.findByRole('status')).toHaveTextContent('Account created. Log in to continue.')
    expect(router.state.location.pathname).toBe('/login')
    expect(router.state.location.search).toBe('?next=%2Fsomewhere')
  })
})
