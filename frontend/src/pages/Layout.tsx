import { useQueryClient } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import { Link, Outlet, useLocation, useNavigate } from 'react-router'

import { logout, restoreSession } from '../api/auth'
import { loginPath } from '../auth/safeNext'
import { useSession } from '../auth/useSession'
import { clearBookingState } from '../booking/holdStore'
import styles from './Layout.module.css'

export function Layout() {
  // Once per page load (restoreSession ignores repeat calls, e.g. StrictMode's second effect run).
  useEffect(() => {
    void restoreSession()
  }, [])

  return (
    <div className={styles.shell}>
      <header className={styles.header}>
        <Link to="/" className={styles.brand}>
          STAMPEDE
        </Link>
        <nav className={styles.nav} aria-label="Account">
          <span className="label">Departures</span>
          <SessionControls />
        </nav>
      </header>
      <main>
        <Outlet />
      </main>
    </div>
  )
}

function SessionControls() {
  const status = useSession()
  const location = useLocation()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const [logoutFailed, setLogoutFailed] = useState(false)

  async function onLogout() {
    try {
      await logout()
      setLogoutFailed(false)
    } catch {
      // The token is gone from memory, but the server may not have revoked the refresh cookie.
      setLogoutFailed(true)
    }
    // Whether or not the server answered: nothing of this user's stays on this (maybe shared)
    // computer, neither fetched data nor the current hold kept in sessionStorage.
    queryClient.clear()
    clearBookingState()
    await navigate('/')
  }

  if (status === 'restoring') {
    return null // avoids showing "Log in" for a moment to someone who is about to be restored
  }
  if (status === 'loggedIn') {
    return (
      <button type="button" onClick={onLogout} className={styles.linkButton}>
        Log out
      </button>
    )
  }
  const onAuthPage = location.pathname === '/login' || location.pathname === '/register'
  return (
    <>
      {logoutFailed && (
        <span role="status" className={styles.warning}>
          Couldn't reach the server. You may still be logged in after a reload.
        </span>
      )}
      {!onAuthPage && <Link to={loginPath(location.pathname + location.search + location.hash)}>Log in</Link>}
    </>
  )
}
