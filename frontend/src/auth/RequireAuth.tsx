import { Navigate, Outlet, useLocation } from 'react-router'

import { loginPath } from './safeNext'
import { useSession } from './useSession'

/**
 * A layout route for pages that need a user. Logged out: go to /login, which comes back here
 * afterwards. While the start-up session restore is still running, wait rather than redirect.
 *
 * Usage in routes.tsx: `{ element: <RequireAuth />, children: [{ path: '/wallet', ... }] }`
 */
export function RequireAuth() {
  const status = useSession()
  const location = useLocation()

  if (status === 'restoring') {
    return (
      <p role="status" className="label">
        Checking your session…
      </p>
    )
  }
  if (status === 'loggedOut') {
    return <Navigate to={loginPath(location.pathname + location.search + location.hash)} replace />
  }
  return <Outlet />
}
