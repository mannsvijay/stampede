import { useSyncExternalStore } from 'react'

import { getSessionStatus, type SessionStatus, subscribeSession } from '../api/auth'

/**
 * The session status, kept in sync with the token in api/auth.ts. useSyncExternalStore re-renders
 * the component whenever that module reports a change, including a refresh failing in the middle of
 * the session (the user is logged out and pages that need a login react at once).
 */
export function useSession(): SessionStatus {
  return useSyncExternalStore(subscribeSession, getSessionStatus)
}
