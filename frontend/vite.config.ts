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
    // prefixes are proxied: app pages live at / and /drops/:id, so reloading a page never hits the API.
    proxy: {
      '/auth': backend,
      '/events': backend,
      '/actuator': backend,
      '/v3': backend,
    },
  },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    restoreMocks: true,
  },
})
