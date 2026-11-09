# Developing Stampede (Windows PowerShell)

Every command here is for Windows PowerShell, run from the repository root unless a step says
`cd backend` or `cd frontend`. Claude Code uses the Git Bash forms of the same commands, listed in
`CLAUDE.md`. PowerShell 5.1 has no `&&`, so run each step on its own line.

## Prerequisites
- Docker Desktop (running). Postgres, Redis and Mailpit run in it, and so do the Testcontainers
  tests and actionlint.
- JDK 21 (Temurin). Gradle itself comes from the wrapper (`.\gradlew.bat`).
- Node at the version in `frontend/.nvmrc`.

## First-time setup: `.env`
The backend and docker-compose both read `.env` in the repository root. It holds secrets, is
git-ignored, and Claude never reads or edits it, so you fill it in yourself.

```powershell
Copy-Item .env.example .env
```

Then open `.env` and:
1. Replace every `change-me` value (Postgres and Redis passwords).
2. Generate the JWT signing key (base64 of 32 random bytes) and paste the output as
   `STAMPEDE_JWT_SECRET`:
   ```powershell
   [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
   ```
   (Git Bash alternative: `openssl rand -base64 32`.)
3. Optional, for the `bootstrap-admin` profile: set `STAMPEDE_ADMIN_EMAIL` and an
   `STAMPEDE_ADMIN_PASSWORD` of at least 12 characters.

## Run everything in Docker
Postgres, Redis, Mailpit and the backend, with the demo data seeded (D-013). Needs `.env` (above).
```powershell
docker compose up -d --build --wait   # build the backend image, start all, wait until healthy
docker compose ps                     # all four should be "healthy"
Invoke-RestMethod http://localhost:8080/events   # the seeded demo event
docker compose logs -f backend        # follow the backend log (Ctrl+C to stop following)
docker compose down                   # stop (keeps data)
```
- The first build takes several minutes (Gradle and dependencies download inside Docker); later
  builds reuse the cache. The image build doesn't run tests; run them with Gradle (below).
- After changing backend code, rebuild: `docker compose up -d --build --wait`.
- The backend container runs with the `seed,demo-reschedule` profiles. To change them, set
  `STAMPEDE_COMPOSE_PROFILES` in `.env` (empty = no seeding). `BACKEND_PORT` changes the host port
  (default 8080). Both are commented out in `.env.example`.
- The frontend runs on the host: `npm run dev` proxies the API to `localhost:8080`, which is the
  container (see Frontend below).
- If compose stops with `required variable ... is missing a value`, that variable is missing or
  empty in `.env`.

## Infrastructure only (for `bootRun`)
The backend container and `bootRun` both use port 8080, so run one at a time. For `bootRun`, start
only the infrastructure:
```powershell
docker compose stop backend                    # if it is running
docker compose up -d postgres redis mailpit    # start Postgres, Redis, Mailpit
docker compose ps                              # all should be healthy
docker compose down                            # stop (keeps data)
```
Reset local data. This deletes the Postgres and Redis volumes:
```powershell
docker compose down -v
docker compose up -d --build --wait
```
The backend container seeds again on start. With `bootRun`, start only the infrastructure and seed
with the `seed` profile (below).

## Backend
```powershell
cd backend
.\gradlew.bat test --rerun                           # all tests; --rerun avoids "UP-TO-DATE" no-op runs
.\gradlew.bat test --rerun --tests "AuthFlowTest"    # one test class
```

Run the app (http://localhost:8080). Profiles are comma-separated:

| Profile | What it does |
|---|---|
| `seed` | Loads the demo venue and event (safe to repeat) |
| `demo-reschedule` | Moves the demo event's drop and start times into the future (SCHEDULED events only) |
| `openapi` | Serves the generated spec at `/v3/api-docs` |
| `bootstrap-admin` | Creates the first ADMIN user from `STAMPEDE_ADMIN_*` in `.env` |

```powershell
cd backend
.\gradlew.bat bootRun --args="--spring.profiles.active=seed,demo-reschedule"
```
Or set the profiles in the environment, and clear them afterwards:
```powershell
$env:SPRING_PROFILES_ACTIVE = "seed,demo-reschedule,openapi"
.\gradlew.bat bootRun
Remove-Item Env:SPRING_PROFILES_ACTIVE
```
Seed once and exit (no web server):
```powershell
.\gradlew.bat bootRun --args="--spring.profiles.active=seed --spring.main.web-application-type=none"
```

Regenerate the demo venue file (`VenueGeneratorTest` fails if the committed file is out of date):
```powershell
.\gradlew.bat generateVenue
```

Update `docs/openapi.json` after an intended API change (`OpenApiSpecTest` fails on drift):
```powershell
cd backend
.\gradlew.bat test --rerun --tests "OpenApiSpecTest"
cd ..
Copy-Item backend\build\openapi\openapi.json docs\openapi.json   # review the diff first
cd frontend
npm run gen:api
```

Port 8080 still in use after stopping `bootRun` (the app JVM can outlive Gradle on Windows):
```powershell
Get-NetTCPConnection -LocalPort 8080 -State Listen | Select-Object OwningProcess
Stop-Process -Id <pid>
```

## Frontend
```powershell
cd frontend
npm ci --ignore-scripts     # install exactly the lockfile, as CI does
npm run dev                 # http://localhost:5173; proxies /auth, /events, /actuator, /v3 to :8080
npm run typecheck
npm run lint
npm test
npm run check:api           # fails if src/api/schema.d.ts is out of date with docs/openapi.json
npm run build
```
Adding or upgrading a package: exact versions, each published at least 14 days earlier, installed
with `npm install --before=<date>` (D-011). More detail: `frontend/README.md`.

## CI
`.github/workflows/ci.yml` runs on pushes to main and on pull requests: the backend tests, and the
frontend typecheck, lint, test, check:api, build and npm audit (D-012).
A third job, `Compose smoke`, builds the backend image, starts the stack with a throwaway `.env`
and checks `GET /events`, the non-root user and that the image holds no `.env` (D-013). Lint the workflow locally:
```powershell
docker run --rm -v "${PWD}:/repo" -w /repo rhysd/actionlint@sha256:b1934ee5f1c509618f2508e6eb47ee0d3520686341fec936f3b79331f9315667
```

## Load tests (from M2.4)
`scripts/k6.sh <scenario>` runs k6 in Docker. Not written yet.
