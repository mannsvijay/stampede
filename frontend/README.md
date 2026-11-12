# Frontend

Vite + React + TypeScript (strict). Decisions: D-011 in `docs/DECISIONS.md`.

## Commands (PowerShell, from `frontend/`)

| Command | What it does |
|---|---|
| `npm install --before=<date>` | Install with exact pinned versions; `--before` keeps transitive dependencies 14+ days old too |
| `npm run dev` | Dev server on http://localhost:5173; proxies /auth, /events, /holds, /orders, /me, /tickets, /actuator, /v3 to the backend on :8080 |
| `npm run typecheck` | `tsc -b` |
| `npm run lint` | ESLint |
| `npm test` | Vitest (jsdom + MSW) |
| `npm run gen:api` | Regenerate `src/api/schema.d.ts` from `../docs/openapi.json` |
| `npm run check:api` | Fail if `src/api/schema.d.ts` is out of date (for CI) |
| `npm run build` | Production build into `dist/` |
| `npm run e2e` | Playwright purchase test against the local stack (see docs/DEVELOPING.md) |

## Layout

- `src/api/`: generated types (`schema.d.ts`, never edited by hand), the typed client and query
  hooks (`client.ts`), the token and refresh logic (`auth.ts`, `http.ts`) and `ApiError` (`problem.ts`).
- `src/time/serverClock.ts`: countdowns follow the server clock (`serverTime`), not the laptop's.
- `src/pages/`: the board (`/`), event (`/drops/:id`), login/register, seats and checkout
  (`/drops/:id/seats`) and wallet (`/wallet`) pages.
- `src/booking/`: the hold and last order kept in sessionStorage; ticket grouping.
- `src/seatmap/`: seat map decoding, geometry and the Canvas2D drawing. `src/qr/`: ticket QR codes.
- `e2e/`: the Playwright purchase test (`npm run e2e`).
- `src/styles/tokens.css`: PRD 7 design tokens.
- `src/test/`: MSW server, fixtures and render helper. An API request without a handler fails the test.
