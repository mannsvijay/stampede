# Session log

## 2026-11-08 — M0.1 Monorepo layout, docker-compose, Gradle backend
**Changed**
- Layout: `backend/`, `frontend/` (placeholder), `scripts/`, `benchmarks/` (placeholder).
- `docker-compose.yml`: postgres:16-alpine, redis:7-alpine (requirepass, AOF), axllent/mailpit:v1.33.1;
  healthchecks, named volumes `pg-data`/`redis-data`, ports bound to 127.0.0.1, creds from `.env`
  (template `.env.example`).
- Backend: Spring Boot 3.5.16, Gradle 8.14.6 (Kotlin DSL), Java 21 toolchain. Starters: web, actuator,
  jdbc, data-redis + postgres driver; test: Testcontainers 1.21.4. `application.yml` imports the
  root `.env`; Redis timeouts set to 2 s so health turns DOWN fast instead of hanging ~60 s.
- `ApplicationHealthTest`: boots against real Postgres + Redis containers, asserts health UP with db + redis UP.
- `.gitattributes` (LF for gradlew/*.sh, CRLF for *.bat), `.gitignore`; `gradlew` committed as 100755.
- `.claude/settings.json` deny rule narrowed (by the user) so `.env.example` is readable.

**Verified**: `.\gradlew.bat test` green; compose all healthy; live `/actuator/health` UP; stopping Redis
-> 503 DOWN in ~2 s, recovers on restart; user re-verified end to end with the real `.env`.

**Next**: M0.2 Flyway V1 schema (PRD 9).

**Open problems / notes**
- Backend not yet in compose (added as M0.10).
- Actuator `show-details: always` is dev-only; restrict before deploy (noted on M7.1).
- Windows: stopping `.\gradlew.bat bootRun` can leave the app JVM holding port 8080; kill it by PID.
- github.com intermittently returned 504 for the Gradle distribution; worked around once by
  downloading into `~/.gradle/wrapper/dists` and verifying SHA-256. CI may hit this; retry if so.

## 2026-11-08 — M0.2 Flyway V1 schema
**Changed**
- `build.gradle.kts`: added `flyway-core` + `flyway-database-postgresql` (versions from the Boot BOM).
- `db/migration/V1__init.sql`: all 18 PRD-9 tables + `user_event_allowance`. UUID v4 ids
  (`gen_random_uuid()` default), named constraints, CHECKs on every status column and on money
  (bigint paise >= 0), timestamptz throughout, `ix_event_seats_event_status`,
  `uq_order_items_active_seat` (partial, WHERE active). Composite same-event FKs (tier/hold/order on
  event_seats; order on order_items and tickets). Status/pointer CHECK on event_seats. Extra partial
  uniques: one live ticket per seat, one VALID check-in per ticket; unique provider_ref; idempotency
  keys PK (user_id, key). audit_log append-only via UPDATE/DELETE row trigger + TRUNCATE statement trigger.
- `SchemaMigrationTest` (13 tests, @JdbcTest + Testcontainers): migration applies cleanly; active-seat
  uniqueness (rejected, then allowed once inactive); invalid statuses, HELD without hold_id and
  negative prices rejected; audit_log rejects UPDATE/DELETE/TRUNCATE. Asserts SQLState + constraint name.
- `docs/PLAN.md`: M0.2 decisions recorded; follow-ups on M0.3, M0.5 (users.role as V2), M1.4, M1.5,
  M1.7, M7.1 (REVOKE-based role separation), offline scanner stretch item.

**Decisions that change the PRD** (user writing these up in DECISIONS.md): UUID ids; seat states
narrowed to AVAILABLE/HELD/SOLD (CHECKED_IN -> tickets, transfer = version bump); transition guard in
code only; event_seats.hold_id authoritative over holds.seat_ids; user_event_allowance table;
tickets.issued_at; proposed status values; extra uniqueness; trigger-based append-only audit log.

**Verified**: `.\gradlew.bat test --rerun` green: ApplicationHealthTest 1/1, SchemaMigrationTest 13/13.

**Next**: M0.3 Venue JSON format + generator script + seed loader.

**Open problems / notes** (all recorded on their PLAN.md tasks)
- orders has no hold_id: decide before M1.5.
- Duplicate seat IDs in a hold must be rejected in code (M1.4).
- Seat venue must match event venue: M0.3 loader + M1.7 invariant check.
- POST /checkins idempotency via scanner-generated checkins.id (offline scanner stretch).
- `.\gradlew.bat test` can report UP-TO-DATE without running anything; use `--rerun` for evidence.

## 2026-11-08 — M0.3 commands (for the M0.9 CLAUDE.md update)
PowerShell form, run from the repo root (`cd backend` first where shown):
- Seed the demo venue + event, then keep serving:
  `cd backend` then `.\gradlew.bat bootRun --args="--spring.profiles.active=seed"`
- Seed once and exit (no web server):
  `cd backend` then `.\gradlew.bat bootRun --args="--spring.profiles.active=seed --spring.main.web-application-type=none"`
- Regenerate the demo venue file: `cd backend` then `.\gradlew.bat generateVenue`
- Reset local data (deletes the Postgres and Redis volumes): `docker compose down -v`, then
  `docker compose up -d`, then seed again

## 2026-11-08 — M0.4 Spring Modulith modules + M0.5 auth
**Changed (M0.4, committed in cd86070)**
- Spring Modulith 1.4.14 (BOM, `spring-modulith-api`, `spring-modulith-starter-test`; the test starter
  also brings `spring-modulith-docs`). Eight modules, each with `api`/`events` named interfaces where
  D-007's table needs them; every module declares `allowedDependencies`. `ModularityTest`: `verify()`
  plus a detection check so it can't pass vacuously. Proven with three illegal references (internal
  type, listen-only module calling an api, dependency outside the table). `allowedDependencies = {}`
  means "nothing allowed"; omitting it means "anything".

**Changed (M0.5, code and tests committed in 01230cd; docs in this commit)**
- Deps: `spring-boot-starter-security`, `-oauth2-resource-server`, `-validation`.
- V2: `users.role` (CHECK), `refresh_token_families` (one row per login: 30-day cap, revocation, and
  the per-session lock), `refresh_tokens` (SHA-256 only, used_at/replaced_by chain).
- POST /auth/register, /login, /refresh, /logout. HS256 access tokens (15 min) from
  `STAMPEDE_JWT_SECRET`; bcrypt 12 with a 72-byte cap; refresh token in an httpOnly SameSite=Strict
  cookie on /auth plus a required `X-Requested-With` header; strict reuse detection; problem+json
  401/403/auth errors; `bootstrap-admin` profile for the first ADMIN.
- New `shared` module (`shared.security`: CurrentUser, Role; `shared.error`: ErrorCode, Problems,
  ApiException); every module allows `shared :: security` and `shared :: error` (probed: `"shared"`
  alone only covers the top-level package).
- Tests: AuthFlowTest 22, AuthConcurrencyTest 68 (25x refresh race, 30x one-refresh-vs-one-logout,
  10x mixed refresh/logout load, 3x register race), JwtFilterTest 17, SecretsAtRestTest (pg_dump),
  AdminBootstrapRunnerTest 5, AuthPropertiesTest 6, SchemaMigrationTest updated for V2 (22).
- Correctness review of RefreshTokenStore (correctness-reviewer agent): no bug in the code. Fixed
  its findings: (1) the old refresh/logout race test passed even with logout disabled (reuse
  detection revoked the family anyway); the new test races exactly one refresh against one logout
  and asserts LOGOUT plus that the racing refresh's token is dead. Proven: with logout temporarily
  a no-op it failed 30/30, while the old mixed race still passed 10/10. (2) The family-cap test now
  asserts Max-Age 3540-3600 s (a session cookie reads as -1) and the child's expires_at = the family
  cap in the DB. (3) rotate checks the family's own expiry under the lock (was a latent 500 from the
  expiry CHECK); new test for it, plus "expired token is rejected without consuming or revoking".
  Hardening: consume uses `AND used_at IS NULL` with a row-count check; READ COMMITTED requirement
  in the Javadoc. lock_timeout deferred to M4.3 (note added).
- Docs: PRD 10 auth error codes; D-008 and D-009 drafted (for rewriting in my own words); PLAN notes
  on M0.5, M0.6, M0.7, M2.4, M2.6, M7.1.

**Found along the way**: the first refresh-token design (revocation on token rows) deadlocked under
20 concurrent refreshes (500 in ~1 run in 5) and could let a token minted by a racing refresh
survive a logout. Fixed with one lock row per family (D-008).

**Verified**
- `.\gradlew.bat test --rerun`: green, 154 tests, 0 failures.
- bootstrap-admin profile verified locally on Windows: the admin was created on the first run and
  left unchanged on the second.

**Next**: M0.6 (GET /events, application-wide problem+json handler; replaces identity's scoped
`AuthExceptionHandler`).

**Open problems**
- No cleanup of expired refresh token families yet (PLAN.md, M0.5).
- Requests for one session queue on its lock while holding a DB connection: rate-limit refresh and
  logout in M4.3.

**Commands (PowerShell, for M0.9)**
- Backend tests: `cd backend` then `.\gradlew.bat test --rerun`
- One test class: `cd backend` then `.\gradlew.bat test --tests "AuthFlowTest"`
- First admin: set `STAMPEDE_ADMIN_EMAIL` and `STAMPEDE_ADMIN_PASSWORD` in `.env`, then
  `cd backend` then `.\gradlew.bat bootRun --args="--spring.profiles.active=bootstrap-admin"`
- Seed and create the admin in one run:
  `cd backend` then `.\gradlew.bat bootRun --args="--spring.profiles.active=seed,bootstrap-admin"`
- Generate a JWT secret in plain PowerShell:
  `[Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))`
  (or `openssl rand -base64 32` from Git Bash)

## 2026-11-09 — M0.6 GET /events, GET /events/{id}; RFC 7807 error handler

**What changed**
- Inventory: `inventory.catalog` (internal) serves `GET /events` (`{items, serverTime}`, upcoming
  non-draft events, ordered by drop time, capped at 100) and `GET /events/{id}` (venue, tiers by
  price, per-user limit; read in one SQL statement). DRAFT ids are 404. No availability counts.
- Errors: one application-wide `ApiExceptionHandler` (extends `ResponseEntityExceptionHandler`) and a
  problem+json `/error` controller in shared's internal `shared.web`; identity's
  `AuthExceptionHandler` deleted. New codes: EVENT_NOT_FOUND, NOT_FOUND, METHOD_NOT_ALLOWED,
  UNSUPPORTED_MEDIA_TYPE, REQUEST_FAILED, INTERNAL_ERROR. 500s carry only a fixed message and an
  `errorId`; security exceptions inside controllers still become 401/403.
- OpenAPI: springdoc 2.10.0 (`-api`, no UI). Generated spec committed at `docs/openapi.json`;
  `OpenApiSpecTest` fails on drift (verified by tampering with the file). `/v3/api-docs` only under
  the `openapi` profile. `@ErrorResponses` documents each operation's codes under their own status;
  response fields are marked required; success media type is application/json.
- Dev: `demo-reschedule` profile moves the demo event's drop/start into the future (SCHEDULED only).
- Docs: PRD 10 (new codes, 401-before-404, spec location), D-009 reworded, D-010 drafted.
- `JwtFilterTest.publicRoutesNeedNoToken` updated with approval: it expected 404 because no /events
  controller existed before M0.6; it now asserts 200 and 404 EVENT_NOT_FOUND.

**Verified**
- `./gradlew test --rerun`: green, 180 tests, 0 failures (ModularityTest and all auth tests included).

**Next**: M0.7 frontend shell, generating the client from `docs/openapi.json`.

**Open problems**
- `RegisterRequest.password` not marked required in the spec (PLAN M0.7 note).
- `/events/{id}/seatmap` needs its own security rule (PLAN M1.8 note).
- `OpenApiConfig` compiles with an unchecked-operations warning from swagger-models' raw types.
- Carried over: no cleanup of expired refresh token families; rate-limit refresh/logout in M4.3.

**Commands (PowerShell, for M0.9)**
- Run with seed, rescheduled demo drop and the spec endpoint:
  `cd backend`, `$env:SPRING_PROFILES_ACTIVE = "seed,demo-reschedule,openapi"`, `.\gradlew.bat bootRun`
  (then `Remove-Item Env:SPRING_PROFILES_ACTIVE`)
- Update the committed spec after an intended API change:
  `cd backend`, `.\gradlew.bat test --tests "OpenApiSpecTest"`, `cd ..`,
  `Copy-Item backend\build\openapi\openapi.json docs\openapi.json`

## 2026-11-09 — M0.7 Frontend shell (written in M0.8 from git history, commit a853965)

**What changed**
- Frontend (D-011): Vite + React 19 + TypeScript 5.9.3, npm with exact pins (`save-exact`) and the
  14-day rule, Node 26 in `.nvmrc`. React Router with `/` (BoardPage: upcoming drops) and
  `/drops/:id` (EventPage: venue, tiers, status), plus a not-found page; TanStack Query; CSS Modules
  with `tokens.css` (PRD 7 colours).
- Generated client: `src/api/schema.d.ts` from the committed `docs/openapi.json` via
  openapi-typescript (`npm run gen:api`; `npm run check:api` fails on drift); openapi-fetch with our
  `authFetch` (access token in memory, one shared refresh on 401, serialised across tabs with the
  Web Locks API, retry once). problem+json errors become `ApiError` with the generated `code` union.
- `formatPaise` for money; `serverClock` corrects countdowns using `serverTime`.
- Vite dev proxy forwards only /auth, /events, /actuator and /v3.
- Tests: Vitest + React Testing Library + jsdom + MSW (unhandled requests fail the test).
- Backend: documentation-only `@Schema` makes `RegisterRequest.password` required (minLength 12) in
  the spec; `OpenApiSpecTest` asserts it; `docs/openapi.json` updated.
- Docs: D-011 drafted; PLAN M0.7 notes.

**Verified** (re-run in the M0.8 session and in CI; the original session's output isn't recorded)
- Frontend: typecheck, lint, 33 tests in 5 files, check:api and build all pass locally and in CI
  run #1 on main. Backend `./gradlew test --rerun`: 180 tests, 0 failures.

**Open problems**
- Resolved here: `RegisterRequest.password` not required in the spec (M0.6 open problem).
- TypeScript 5.9.3 pin: revisit when typescript-eslint and openapi-typescript support 7.
- minimatch advisory in lint tooling: accepted for now (D-011), override in PLAN M0.7a.
- No login UI yet (M1.8).

## 2026-11-09 — M0.8 GitHub Actions CI

**What changed**
- `.github/workflows/ci.yml`: push to main, pull_request, workflow_dispatch; `contents: read`;
  superseded PR runs cancelled (main runs never are). Jobs `Backend` (Temurin 21, setup-gradle with
  wrapper validation and cache, wrapper warm-up retried 3x for the github.com 504, `./gradlew test`,
  reports uploaded on failure) and `Frontend` (Node from `.nvmrc`, `npm ci --ignore-scripts`,
  typecheck, lint, test, check:api, build, blocking prod audit at high, report-only full audit).
  Actions pinned to commit SHAs, each release 14+ days old.
- `gradle-wrapper.properties`: `distributionSha256Sum` for gradle-8.14.6-bin.zip.
- Docs: D-012 drafted; D-011 records the minimatch finding as accepted for now; PLAN M0.7a added
  for the override.

**Verified (locally)**
- actionlint 1.7.12 (Docker, pinned by digest, shellcheck 0.11.0 inside): 0 errors.
- Frontend after `npm ci --ignore-scripts` on Node 26.13.0: typecheck, lint, test (33/33),
  check:api, build all exit 0; `npm audit --omit=dev --audit-level=high` exit 0 (0 found); full
  `npm audit` exit 1 (10 moderate, minimatch; report-only in CI).
- `./gradlew test --rerun`: BUILD SUCCESSFUL, 180 tests, 0 failures/errors/skipped.
- Fresh `GRADLE_USER_HOME`: `./gradlew --version` downloaded 8.14.6 and passed the new checksum.

**Verified (on GitHub, by the user)**
- CI run #1 on main (commit 2a4ece4): Backend and Frontend green, 2m 32s.
- Draft PR #1 from `ci/prove-red` with the money test deliberately broken: Frontend failed, Backend
  passed, so a failing test turns CI red. PR closed, branch deleted; main never had the commit.

**Next**: M0.9 update CLAUDE.md commands with the real ones.

**Open problems**
- M0.7a minimatch override, not before 2026-11-10T22:26Z.
- main is not protected (D-012); add a ruleset with a self-bypass later if wanted.
- Pinned action SHAs are bumped by hand (no Dependabot).
- M0.7 was unticked with no log entry although its code was committed; fixed after M0.8 (ticked,
  entry above written from git history).

**Commands (PowerShell, for M0.9)**
- Lint the workflow: `docker run --rm -v "${PWD}:/repo" -w /repo rhysd/actionlint@sha256:b1934ee5f1c509618f2508e6eb47ee0d3520686341fec936f3b79331f9315667`
- Frontend as CI runs it: `cd frontend`, `npm ci --ignore-scripts`, then `npm run typecheck`,
  `npm run lint`, `npm test`, `npm run check:api`, `npm run build`

## 2026-11-09 — M0.9 Real commands in CLAUDE.md

**What changed**
- `CLAUDE.md`: the placeholder Commands section replaced with the real Git Bash commands (infra,
  backend tests, bootRun profiles, generateVenue, the openapi.json update, frontend scripts,
  actionlint, k6 marked as M2.4) and a Gotchas section (`--rerun`, Docker needed, the JVM left on
  :8080, never read or edit `.env`, MSYS path conversion for Docker `-v`).
- `docs/DEVELOPING.md` (new): the same commands in PowerShell form, plus `.env` setup and JWT
  secret generation. Root `README.md` (new): CI badge and links; M7.2 replaces it.
- `.claude/settings.json`: allow `npm test`, `npm ci --ignore-scripts`, `npm audit` and
  `npm audit --omit=dev --audit-level=high`, `docker ps`, `git status *`, `git show *`. New `ask`
  rules for `docker compose down -v`/`--volumes`. Extra deny rules: `Read(**/.env)` and `Edit` on
  the .env files. No existing rule removed or loosened.

**Verified**
- settings.json parses as JSON.
- `./gradlew help --task bootRun` resolves; `./gradlew generateVenue` leaves the seed file unchanged.
- Spec update flow: `OpenApiSpecTest` passes, copying the generated spec and `npm run gen:api` both
  leave no diff.
- Frontend: `npm ci --ignore-scripts`, typecheck, lint, check:api, build exit 0; `npm test` 33/33.
- actionlint (the CLAUDE.md command, verbatim): exit 0. The :8080 netstat lookup runs in Git Bash.
- `./gradlew test --rerun`: BUILD SUCCESSFUL, 180 tests, 0 failures/errors/skipped.
- Not run by Claude: `bootRun` itself (needs the user's `.env`).

**Next**: M0.10 backend Dockerfile + `backend` service in docker-compose (the M0 exit criterion).

**Open problems**
- The new permission rules take effect in the next session; check there that `npm test` doesn't
  prompt and `docker compose down -v` does.
- `Bash(cat .env)` isn't covered by Read deny rules; CLAUDE.md's rule covers it.
- frontend/README.md repeats the frontend commands (kept; DEVELOPING.md links to it).

## 2026-11-10 — M0.10 Backend container and compose (M0 exit criterion)

**What changed** (commits 94c9917, d977d72, 05edf51; decisions in D-013)
- `backend/Dockerfile` (new): JDK build stage, wrapper download retried 3x (the github.com 504),
  Gradle home as a BuildKit cache mount, `bootJar` only; JRE runtime stage as uid 10001
  `stampede`. Both Temurin 21.0.12+8 noble, pinned by digest (the 21.0.13 builds were under 14
  days old). `backend/.dockerignore` (new): `.env*`, build output, `node_modules`.
- `docker-compose.yml`: `backend` service, waits for healthy postgres/redis, explicit environment
  (service names, internal ports, secrets from `.env` with `:?`), `127.0.0.1:${BACKEND_PORT:-8080}`,
  curl healthcheck on `/actuator/health`, profiles `${STAMPEDE_COMPOSE_PROFILES-seed,demo-reschedule}`.
  No fixed image name, so clones don't overwrite each other's image.
- `application.yml`: `POSTGRES_HOST`/`REDIS_HOST`, default `localhost`. `.env.example`: optional
  `BACKEND_PORT` and `STAMPEDE_COMPOSE_PROFILES`.
- CI: `Compose smoke` job (throwaway `.env` with `openssl rand` secrets, build, `up --wait`,
  GET /events, non-root, no `.env` in image or jar, `down -v`). No new actions.
- Docs: PLAN exit criterion reworded (backend + infra in compose, frontend on `npm run dev`),
  M7.1 seeding review note; DEVELOPING.md and CLAUDE.md compose workflow; DEVELOPING.md JWT
  command fixed for Windows PowerShell 5.1.

**Verified**
- Local: cold image build 6m 48s; `up -d --wait` all 4 healthy; `/events` has the demo event;
  `id` in the container is uid 10001; `find / -name '.env*'` in the image (as root) is empty,
  0 `.env` entries in `app.jar`; a missing variable fails with its name; actionlint clean;
  `./gradlew test --rerun` 180 tests, 0 failures/errors/skipped.
- CI run #7 on main (05edf51): Backend, Frontend and Compose smoke green (reported by the user;
  `gh` isn't authenticated here). M0 exit criterion met.
- Not run by Claude: the new JWT command in PowerShell 5.1 (blocked); the clean-clone steps in a
  separate folder (given to the user).

**Next**: M1, starting with M0.7a (minimatch override) once 2026-11-10T22:26Z has passed.

**Open problems**
- Bump the Temurin digests to 21.0.13 (October security update) from 2026-11-12.
- `docker compose up -d` now starts the backend on :8080; use `up -d postgres redis mailpit` for bootRun.
- Compose smoke builds cold every run (no layer cache), which sets CI's wall-clock time.
- `docs/SESSION_LOG.md` M0.5 entry still shows the PowerShell-7-only JWT command (historical).
- Actuator `show-details: always` until M7.1.
