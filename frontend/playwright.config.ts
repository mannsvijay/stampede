import { defineConfig, devices } from '@playwright/test'

/**
 * The end-to-end purchase test (e2e/), run locally against the compose stack started with the
 * `seed,demo-open-sale` profiles (docs/DEVELOPING.md). Not in CI yet (PLAN M7.3 note).
 * Chromium only: the test proves the flow, not browser compatibility.
 */
export default defineConfig({
  testDir: './e2e',
  timeout: 60_000,
  retries: 0,
  reporter: [['list'], ['html', { open: 'never' }]],
  use: {
    baseURL: 'http://localhost:5173',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  // Starts the Vite dev server (which proxies the API to the backend on :8080), or reuses one
  // that is already running.
  webServer: {
    command: 'npm run dev',
    url: 'http://localhost:5173',
    reuseExistingServer: true,
    timeout: 60_000,
  },
})
