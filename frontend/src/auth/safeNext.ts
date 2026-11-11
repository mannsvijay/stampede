/**
 * Where to go after logging in, from the untrusted `?next=` query parameter.
 *
 * Anyone can send a link like /login?next=https://evil.example, so following `next` blindly would
 * make our login page an open redirect: the user logs in on the real site and lands on a fake one.
 * Only paths inside this app are accepted; anything else becomes "/".
 */
export function safeNext(next: string | null): string {
  if (next === null || !next.startsWith('/') || next.startsWith('//') || hasUnsafeCharacter(next)) {
    return '/'
  }
  let url: URL
  try {
    url = new URL(next, window.location.origin)
  } catch {
    return '/'
  }
  if (url.origin !== window.location.origin || url.pathname === '/login' || url.pathname === '/register') {
    return '/'
  }
  return url.pathname + url.search + url.hash
}

/** The login page URL that returns to `path` afterwards. */
export function loginPath(path: string): string {
  return `/login?next=${encodeURIComponent(path)}`
}

/**
 * Browsers treat "\" like "/" in URLs (so "/\evil.example" means "//evil.example", another site) and
 * silently drop tabs and newlines. Real app paths contain neither, so reject them outright.
 */
function hasUnsafeCharacter(value: string): boolean {
  for (const char of value) {
    const code = char.charCodeAt(0)
    if (char === '\\' || code < 0x20 || code === 0x7f) {
      return true
    }
  }
  return false
}
