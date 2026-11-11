import react from '@vitejs/plugin-react'
import { defineConfig } from 'vitest/config'

const backend = 'http://localhost:8080'

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    strictPort: true,
    // The dev proxy makes the API same-origin with the app, so the httpOnly refresh cookie
    // (SameSite=Strict, Path=/auth) is sent, and no CORS is needed (D-008, D-011). Only API
    // prefixes are proxied, and no app page may start with one (see routes.tsx): reloading a page
    // must load the app, never the API's JSON.
    proxy: {
      '/auth': backend,
      '/events': backend,
      '/holds': backend,
      '/orders': backend,
      '/me': backend,
      '/tickets': backend,
      '/actuator': backend,
      '/v3': backend,
    },
  },
  test: {
    environment: 'jsdom',
    // Unit and page tests only: e2e/*.spec.ts are Playwright's (npm run e2e).
    include: ['src/**/*.test.{ts,tsx}'],
    setupFiles: ['./src/test/setup.ts'],
    restoreMocks: true,
  },
})
