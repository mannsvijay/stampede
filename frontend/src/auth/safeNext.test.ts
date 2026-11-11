import { describe, expect, it } from 'vitest'

import { loginPath, safeNext } from './safeNext'

describe('safeNext', () => {
  it.each([
    ['/', '/'],
    ['/drops/abc', '/drops/abc'],
    ['/drops/abc/seats?tier=gold#map', '/drops/abc/seats?tier=gold#map'],
    ['/wallet', '/wallet'],
  ])('keeps the in-app path %s', (next, expected) => {
    expect(safeNext(next)).toBe(expected)
  })

  it.each([
    [null, 'missing'],
    ['', 'empty'],
    ['https://evil.example/login', 'absolute URL'],
    ['//evil.example', 'protocol-relative URL'],
    ['/\\evil.example', 'backslash, which browsers read as //evil.example'],
    ['/\t/evil.example', 'tab, which browsers drop (leaving //evil.example)'],
    ['/drops\n/x', 'newline'],
    ['javascript:alert(1)', 'javascript: URL'],
    ['drops/abc', 'relative path'],
    ['/login', 'the login page itself (a loop)'],
    ['/login?next=/x', 'the login page with its own next'],
    ['/register', 'the register page'],
  ] as [string | null, string][])('rejects %j (%s) and falls back to /', (next) => {
    expect(safeNext(next)).toBe('/')
  })

  it('loginPath encodes the return path so its own query string survives', () => {
    expect(loginPath('/drops/a?x=1&y=2')).toBe('/login?next=%2Fdrops%2Fa%3Fx%3D1%26y%3D2')
  })
})
