# Frontend

Vite + React + TypeScript (strict). Decisions: D-011 in `docs/DECISIONS.md`.

## Commands (PowerShell, from `frontend/`)

| Command | What it does |
|---|---|
| `npm install --before=<date>` | Install with exact pinned versions; `--before` keeps transitive dependencies 14+ days old too |
| `npm run dev` | Dev server on http://localhost:5173; proxies /auth, /events, /actuator, /v3 to the backend on :8080 |
| `npm run typecheck` | `tsc -b` |
| `npm run lint` | ESLint |
| `npm test` | Vitest (jsdom + MSW) |
| `npm run gen:api` | Regenerate `src/api/schema.d.ts` from `../docs/openapi.json` |
| `npm run check:api` | Fail if `src/api/schema.d.ts` is out of date (for CI) |
| `npm run build` | Production build into `dist/` |

## Layout

- `src/api/`: generated types (`schema.d.ts`, never edited by hand), the typed client and query
  hooks (`client.ts`), the token and refresh logic (`auth.ts`, `http.ts`) and `ApiError` (`problem.ts`).
- `src/time/serverClock.ts`: countdowns follow the server clock (`serverTime`), not the laptop's.
- `src/pages/`: the board (`/`) and the event page (`/drops/:id`).
- `src/styles/tokens.css`: PRD 7 design tokens.
- `src/test/`: MSW server, fixtures and render helper. An API request without a handler fails the test.
