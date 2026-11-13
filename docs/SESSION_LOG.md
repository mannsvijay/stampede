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

## 2026-11-10 — M1.1 Seat state machine (commit ca1b7d6)

**What changed**
- `inventory.seat` (internal, D-007): `EventSeat` (immutable record), `SeatStatus`, `SeatTransition`,
  `IllegalSeatTransitionException` (reason WRONG_STATE or HOLD_MISMATCH, no HTTP meaning).
  Transitions: hold (AVAILABLE to HELD); release, expire, failSaga (HELD to AVAILABLE, owning hold
  only); sell (HELD to SOLD, clears hold_id). SOLD is final. Every transition bumps `version`. The
  constructor mirrors `ck_event_seats_status_pointers` exactly.
- Tests: `EventSeatTest` (39: the full 3x5 table, written by hand and checked for completeness, hold
  mismatch, nulls, every CHECK shape); `EventSeatPropertiesTest` (4 jqwik properties, 1000 tries
  each: every reachable seat passes the CHECK, SOLD is final, every step follows the transition
  table, constructor accepts exactly what the CHECK accepts).
- New test dependency `net.jqwik:jqwik:1.9.2`; `.jqwik-database` gitignored.
- Docs: D-014 (DRAFT); PLAN notes on M1.4 (error mapping, HOLD_EXPIRED status) and M1.5 (when
  `order_id` is set, sell overwriting a different order_id).

**Verified**
- Temporarily allowed HOLD from SOLD: `soldIsFinal` and `everyStepFollowsTheTransitionTable` failed;
  jqwik shrank the second to `AVAILABLE: [HOLD(A), SELL(A, ORDER_A), HOLD(A)]`. Reverted, green.
  `everyReachableSeatPassesTheDatabaseCheck` did *not* catch it (SOLD to HELD with a hold_id is a
  valid row), which is why the transition-table property exists.
- `./gradlew test --rerun`: 223 tests, 0 failures/errors/skipped, ModularityTest green.
- `dependencyInsight`: jqwik 1.9.2 requests JUnit Platform 1.11.3, resolved to Boot's 1.12.2.

**Next**: M0.7a (minimatch override) after 2026-11-10T22:26Z; then M1.2 (pessimistic strategy,
written by hand, Claude reviews).

**Open problems**
- jqwik's shrink for `soldIsFinal` stopped at 5 steps with two redundant rejected HOLDs (not
  minimal); the other property shrank to the minimal 3. Cosmetic.
- 90% of generated steps are rejected (seats get SOLD and stay there). One run still made 1,417
  legal steps (AVAILABLE to HELD 579, HELD to AVAILABLE 610, HELD to SOLD 228); revisit the generator if a property needs deeper paths.

## 2026-11-10 — M1.2 InventoryStrategy + pessimistic strategy

**What changed**
- `inventory.strategy` (internal, D-007): `InventoryStrategy` (`hold`, `release`, `kind`),
  `HoldRequest` (caller-generated holdId, no duplicate seats, `Duration holdFor` >= 1 s),
  `HoldOutcome` (`Held` with DB-computed `expiresAt` / `Rejected` SEAT_TAKEN | UNKNOWN_SEAT),
  `ReleaseOutcome` (`Released` / `NotActive` / `NotExpired` / `NotFound`), `ReleaseReason`,
  `StrategyKind`. Interface, skeleton and first 13 tests in d029688.
- `PessimisticInventoryStrategy`: plan changed mid-task, so Claude wrote it (not by hand) from the
  13-step guide. hold: `ORDER BY seat_id FOR UPDATE`, unknown check, EventSeat decides every seat
  before any write, holds row with `now() + holdFor`, seat UPDATEs with `AND version = :old`.
  release: hold row `FOR UPDATE` first, re-check expiry for EXPIRED, then seats in seat_id order,
  consistency check (owned seats = `seat_ids`), state machine, hold status.
- Tests: `PessimisticInventoryStrategyTest`, 21 (Testcontainers, no test transaction), including
  deterministic lock tests (lock order, wait-then-reread via `pg_stat_activity` + `NOWAIT`), rollback
  after a failed write (test-only trigger), cancel vs sweeper race.
- Docs: D-015 (DRAFT): interface, lock order, database clock (`now()` kept, decided), new holdId per
  attempt, callers not transactional. PLAN notes on M1.4 (sweeper, allowance placement,
  LIMIT_EXCEEDED) and M2.4 (`lock_timeout`, pool exhaustion).

**Verified**
- Experiments (all restored): no `FOR UPDATE` → race tests failed 5/5, caught by the version check
  (rollback, no double-hold); no `FOR UPDATE` + no version check → both buyers `Held` the same seat
  3/3; no `ORDER BY` → lock-order test failed 3/3 (the old opposite-order test still passed: it
  can't detect lock order, now labelled a smoke test).
- Java 21 vs Postgres 16 UUID order checked: `9000…` first in Java, last in Postgres.
- correctness-reviewer: no race/deadlock/all-or-nothing bug; fixed expiry re-check, min holdFor and
  four test gaps; holdId reuse judged a caller bug (documented), propagation/allowance/lock_timeout
  deferred to M1.4/M2.4 notes.
- `./gradlew test --rerun`: 244 tests, 0 failures/errors/skipped, ModularityTest green.

**Next**: M1.2a explain-back quiz; M0.7a (minimatch override, after 2026-11-10T22:26Z); M1.3
(concurrency harness x1000).

**Open problems**
- The old expiry test changed its setup to an already-expired hold (the new guard forbids expiring a
  live hold); assertions unchanged.
- The race tests rely on a latch, so overlap is likely, not guaranteed; the deterministic lock tests
  and M1.3 carry the proof.
- `@Transactional` REQUIRED: a transactional caller would batch releases (rule in D-015, unenforced).

## 2026-11-10 — M1.3 Concurrency harness

**What changed**
- Test code in `inventory.strategy` (src/test, next to the strategy tests; package access to the
  package-private strategy): `ConcurrencyHarness` (takes any `InventoryStrategy`), `HarnessScenarios`
  (singleSeat, overlappingSets, churn), `HoldInvariants` (I1-I3 as SQL, for M1.7 to take over),
  `HarnessReport` (counts, p50/p99, failures with round seed), `PessimisticConcurrencyTest`
  (16 threads, Hikari pool 20 for this test only, asserted).
- Checks after every round: I1 seat in two active holds, I2 held seat without its active hold,
  I3 active hold not owning its seat, I4 winners (exactly 1 for one seat, disjoint live winners,
  at least 1 per overlapping round), I5 database = outcomes (HELD seats and ACTIVE holds), I6 version =
  number of successful transitions (lost updates), I7 zero exceptions (classified by SQLState).
- Rounds: default 1000 (`-Dstampede.harness.rounds`), seed `-Dstampede.harness.seed`; Gradle
  forwards `stampede.harness.*` to the test JVM. CI: `./gradlew test -Dstampede.harness.rounds=100`;
  actionlint clean. No new dependency.

**Verified (the M1 exit criterion "concurrency test passes 1000/1000")**
- Local, pessimistic, 16 threads: singleSeat 1000/1000 in 71.7 s, overlappingSets 1000/1000 in
  95.4 s, churn 1000/1000 in 94.4 s; 261.5 s total; 0 violations, 0 exceptions. Over the 90 s
  threshold, so CI stays at 100 rounds.
- Experiment A (temporary): no `FOR UPDATE` + no version check -> singleSeat and overlappingSets
  failed 100/100 rounds (16 winners for one seat; I1, I3, I4, I5, I6), churn 50/100 (plus I7 from
  release's own consistency check).
- Experiment B (temporary): no `ORDER BY seat_id` -> real deadlocks (40P01): churn 99/1000 rounds
  (188 deadlocks), overlappingSets 4/1000 (15), singleSeat clean. Data stayed correct (only I7);
  deadlocks cost about 1 s each (p99 1047 ms, max 4.3 s). My prediction that hold-vs-hold could not
  deadlock was wrong: concurrent UPDATEs move rows in the heap, so two scans can see different orders.
- Strategy file restored; `git diff` on src/main empty.
- Final `./gradlew test --rerun` (9 min 3 s): 247 tests, 0 failures/errors/skipped, ModularityTest
  green; the harness inside it was 1000/1000 clean again with fresh seeds (68.6 s, 92.1 s, 91.4 s).

**Next**: M1.2a explain-back quiz; M0.7a (minimatch override, after 2026-11-10T22:26Z); M1.4.

**Open problems**
- A seed replays a round's inputs, not its interleaving.
- The full suite now includes ~4.4 min of harness locally (default 1000 rounds).

## 2026-11-10 - M1.4 Holds: create, release, expiry sweeper (and M1.2a dropped)

**Changed**
- M1.2a (explain-back quiz) removed from PLAN by my choice.
- Strategy: per-user cap as step 4b (one `user_event_allowance` upsert inside the hold transaction;
  `LIMIT_EXCEEDED` added to `HoldRejection`), allowance given back as step 11b on every release
  reason, `takenAgoMs` on `Rejected` (owner's `created_at`, read while the seats are locked).
- `inventory.api.HoldService` + `HoldResult`/`CancelResult`; `InventoryStrategies` (strategy by
  `events.strategy`); `inventory.holds`: `HoldController` (POST /events/{id}/holds, DELETE
  /holds/{id}), `HoldExpirySweeper` (keyset pages, one hold per transaction), `HoldSweeperSchedule`
  (5 s fixed delay, `stampede.holds.sweeper.*`).
- Errors: SEAT_TAKEN, LIMIT_EXCEEDED, SALE_NOT_OPEN, HOLD_CONVERTED (409), HOLD_NOT_FOUND (404);
  `ApiException` carries extra problem properties (`seatIds`, `takenAgoMs`, `limit`).
- Dev profile `demo-open-sale` (demo drop one minute ago), documented in CLAUDE.md, DEVELOPING.md,
  `.env.example`.
- Harness: allowance rows reset, invariant I8, per-scenario limit, `capRace`; new
  `HoldCapConcurrencyTest`; default rounds now 100 (as CI), full proof with
  `-Dstampede.harness.rounds=1000` (CLAUDE.md, DEVELOPING.md, ci.yml comment).
- docs/openapi.json and frontend `schema.d.ts` regenerated. D-016 drafted; D-005 cross-reference.
  No new dependency.

**Verified**
- Final `./gradlew test --rerun` (17 min 33 s, 1000 harness rounds): 293 tests, 0 failures,
  errors, skipped. Every harness scenario 1000/1000 clean (times in PLAN M1.3).
- Mutation: cap's limit check removed -> capRace failed 20/20 rounds (I8, I9); restored.
- Correctness-reviewer agent: no bugs; fixed a vacuous two-sweeper test (overlap now forced) and
  sweeper starvation by broken holds (keyset paging).
- After the default change: `*ConcurrencyTest` at 100 rounds green (6 scenarios clean); actionlint
  clean. Frontend: typecheck, lint, test (33), check:api, build green.

**Next**: M0.7a (minimatch override, after 2026-11-10T22:26Z); M1.5 orders from holds (add
HOLD_EXPIRED 410; CONVERTED keeps the allowance; I8 counts orders).

**Open problems**
- Sale-window/strategy read is unlocked (a hold can land just after a pause); D-016 Known limits.
- I8 treats `used > per_user_limit` as drift; wrong if a limit is ever lowered (M1.7).
- In-flight retry (second POST waiting on the first's locks) untested.
- `/holds` not in the Vite proxy yet (M1.8). Refresh-token family cleanup still open (M0.5).

## 2026-11-10 - M1.5 Orders from holds (synchronous fake payment)

**Changed**
- Flyway V3: `orders.hold_id` NOT NULL, FK (hold_id, event_id) -> holds, `uq_orders_hold` UNIQUE.
- `booking.checkout`: `CheckoutService` (not @Transactional) runs the saga steps one transaction
  each: hold check, PENDING order + items (`OrderWriter`), payment, sell, PAID (3 attempts).
  `OrderController`: POST /orders (201 new, 200 existing order for the same hold), GET /orders/{id}
  (owner only). `OrderStatus` guard; `OrderQueries` scoped to the caller.
- Best-effort compensation after step 1: release the hold first (definitive under its lock; a
  CONVERTED hold means finish instead), then cancel the order (items inactive), then refund. Never
  undoes a sale. Failure table and known limits in D-017.
- `payments`: `PaymentService` (row before the provider call; unknown outcome stays CREATED),
  `PaymentStatus` guard, `PaymentProvider` interface + `FakePaymentProvider` (always succeeds).
- `inventory`: `InventoryStrategy.sell` (hold row, then seats ORDER BY seat_id; ACTIVE and
  `expires_at > now()`; hold CONVERTED; allowance untouched) in the pessimistic strategy;
  `HoldService.checkoutHold` / `sellForOrder` / `releaseForFailedCheckout`.
- Errors: HOLD_EXPIRED 410, PAYMENT_FAILED 402, ORDER_NOT_FOUND 404; SEAT_TAKEN 409 on POST
  /orders for a seat still in another order's active items.
- Tests: OrderApiTest, CheckoutFailureTest (spies: sell throws -> no seat stuck; sell committed
  then threw -> PAID; finish retried; release throws -> nothing cancelled or refunded; decline;
  provider outage; expiry during payment), OrderConcurrencyTest (overlap forced by a barrier,
  7 duplicate-key losers per round), sell tests in PessimisticInventoryStrategyTest, V3 schema
  tests. I8 counts ACTIVE + CONVERTED holds; `HoldInvariants` public; order rules O1-O5.
- Fixed flaky `JwtFilterTest.tamperedSignatureIs401` (it flipped padding bits of the last char).
- docs: D-017 (draft), PRD 10 checkout errors and the Idempotency-Key deviation, openapi.json
  and frontend `schema.d.ts` regenerated, PLAN follow-ups. No new dependency.

**Verified**
- `./gradlew test --rerun` (100 rounds): 326 tests, 0 failures/errors/skipped, 5 min 30 s.
- `*ConcurrencyTest` with 1000 rounds: 75 tests green; every harness scenario 1000/1000 clean,
  0 invariant violations, 0 exceptions (9 min 40 s).
- JwtFilterTest fix: 50/50 as a temporary @RepeatedTest(50).
- Mutation: duplicate-hold branch disabled -> OrderConcurrencyTest failed; restored.
- Correctness-reviewer agent: no invariant bug; six findings fixed, the rest in D-017.
- Frontend: typecheck, lint, test (33), check:api, build green.

**Next**: M0.7a (minimatch override, date-gated); M1.6 ticket issuing (Ed25519, QR, GET
/me/tickets): add a step after SEATS_SOLD, keeping the step-per-transaction shape.

**Open problems**
- A process crash between checkout steps leaves a PENDING order whose active items block its seats
  (409 SEAT_TAKEN) until M3.4 recovery; same for "PAID never recorded" and "release throws".
- A checkout still paying on an ended hold blocks other buyers' orders for those seats until its
  step 3 (seconds now, minutes with async payment in M3).
- Payments with an unknown outcome stay CREATED until the M3.5 reconciliation poller.
- POST /orders has no Idempotency-Key (M3.1); `/orders` and `/holds` not in the Vite proxy (M1.8).

## 2026-11-11 - M1.6 Ticket issuing: Ed25519, QR payload, GET /me/tickets

**Changed** (committed as f181194, then the correctness-review fixes as 3c2cacf)
- Saga step 5 (TicketsIssued) after PAID: `booking.checkout.TicketStep`, one booking transaction:
  lock the order row (`FOR NO KEY UPDATE`), require PAID/SEATS_SOLD, seats = the order's active
  items, `ticketing::api` `TicketIssuer.issueForOrder` (joins), record TICKETS_ISSUED. 3 attempts;
  on failure the order stays PAID/SEATS_SOLD, 201, logged; a retried POST /orders for the
  CONVERTED hold issues them.
- `TicketIssuer`: one insert per seat with `ON CONFLICT ... DO NOTHING` on `uq_tickets_live_seat`,
  seats in id order, then every seat's live ticket must be this order's (else roll back, throw).
- Signing (`ticketing.signing`): JDK Ed25519 from a 32-byte seed (`STAMPEDE_TICKET_SIGNING_KEY`;
  app won't start without a valid one); the seed workaround isolated in `Ed25519Keys`; 81-byte
  binary payload, 195-char QR string, canonical text only; key id = first 4 bytes of SHA-256 of
  the public key. Spec + test vector: docs/ticket-format.md.
- `GET /me/tickets` (caller's tickets, labels, QR string rebuilt from the row), public
  `GET /tickets/keys` (JWKS). V4: `tickets.key_id`, seat-label snapshot, signature length check.
- Inventory's `CheckoutHold` seats carry labels; a CONVERTED hold returns its seats (retry path).
- Config: application.yml, docker-compose, .env.example (PowerShell generator), test key, CI
  "Create .env" step, DEVELOPING.md. No new dependency.
- Tests: Ed25519KeysTest (RFC 8032), TicketSigningPropertiesTest, TicketSignerTest (every bit of
  payload and signature, forged key, canonical text, known-answer vector), TicketsApiTest (one
  per seat, idempotent, cross-order refusal, TicketStep checks, wallet scoping, offline verify via
  GET /tickets/keys), TicketIssuingConcurrencyTest, step-5 cases in CheckoutFailureTest, V4 schema
  tests, ticket rules T1-T3. D-018 (draft).

**Verified**
- `./gradlew test --rerun`: 370 tests, 0 failures/errors/skipped (6 min 9 s), on the final code.
- Mutation: check-then-insert instead of ON CONFLICT -> issuing concurrency test failed; restored.
- A second issuer is seen waiting in pg_locks while the first transaction is open; two-order race
  exercises both winners (17/30 for Alice in the final run).
- Correctness-reviewer agent: no live bug; four findings fixed, the rest recorded in D-018.
- Frontend typecheck, lint, test (33), check:api, build green; actionlint 0 errors.

**Next**: M1.7 invariant checker (take over I1-I3, I8, O1-O5, T1-T3; grace period for T3 and for
PENDING orders), then M1.8 seat map with the ticket QR.

**Open problems**
- A PAID order can stay without tickets until the buyer retries or M3.4 recovery; the 201 response
  doesn't tell the client (D-018 known limits).
- `TicketIssuer` can't check PAID or seat ownership itself (D-007); every caller must (TicketStep does).
- One signing key, no rotation; changing it invalidates earlier tickets.
- CI's Compose smoke job with the new key was not run locally (needs your .env); `.env` needs
  `STAMPEDE_TICKET_SIGNING_KEY` before `docker compose up`.

## 2026-11-11 - M1.7 Invariant checker v1 + hard assertion after every test

**What changed**
- `control.invariants.InvariantChecker`: 16 SQL rules per event in one read-only REPEATABLE READ
  snapshot. Moved word for word from the tests: I1-I3, I8 (`HoldInvariants`, deleted), O1-O5 and
  T1-T3 (`CheckoutTestSupport`). New: V1 (seat in the event's venue), O6 (seat in two active
  orders), O7 (stuck PENDING order), C1 (capacity). `InvariantCheck` interface is the slot for the
  M2.2 Redis rule.
- Grace (from `orders.created_at`) excuses a running checkout's in-between states (O1, O4, O5, O7,
  T3); tests check strictly.
- `testsupport.InvariantAssertionListener` (spring.factories): checks every event's committed state
  after every Spring test; for tests inside a test transaction, after its rollback.
  `@ExpectInvariantViolations(rules, reason)` must match exactly: 16 tests annotated, 5 half-built
  setups fixed (`sellDirectly`, `holdWaits…`, two `HoldApiTest` cases; `HoldApiTest` cleans orders).
  `-Dstampede.invariants.after-test=report` lists instead of failing.
- `InvariantMonitor` + schedule: every 30 s, 30 s grace, non-DRAFT/ENDED events; ERROR log + gauge
  `stampede.invariants.violations{rule}`. Off in tests.
- Harness and `CheckoutTestSupport` call the checker. D-019 (draft); PLAN notes on M2.2, M2.3, M3.4,
  M5.5. No new dependency.

**Verified**
- `./gradlew test --rerun`: 407 tests, 0 failures/errors/skipped (7 min 24 s), hard mode; six harness
  scenarios 100/100 clean; ModularityTest green.
- InvariantCheckerTest: every rule fires on a deliberate violation (O6 by dropping the unique index
  in a rolled-back transaction in its own container: the one recorded exception). Sold-out
  2,000-seat event: full check median 83 ms.
- Step-3 report run flagged 21 tests; each was reviewed with the user before annotating or fixing.

**Next**: 1000-round proof (M1 exit criterion), then M1.8 seat map with the ticket QR.

**Open problems**
- First report run deadlocked (listener vs an open test transaction's TRUNCATE locks); fixed by
  checking after the rollback and a 10 s `lock_timeout`. No dedicated test proves the after-rollback
  path catches a violation (D-019 known limits).
- `/actuator/metrics` isn't exposed, so the gauge isn't visible outside the app yet (M5.5).
- Grace is measured from order creation; M3's async payment may need a longer grace or `updated_at`.
- `TicketsApiTest` refund annotation to revisit once M3 defines a refund's end state.
- Not done this session: a correctness-reviewer pass and the stuck-sweeper rule (moved to M2.2).
- Stale `backend/build/reports/invariants/after-test.txt` from the report run (build output, safe
  to delete).

## 2026-11-11 - M1.8a Seat map API (layout + availability bitset), proxy entries

**What changed**
- M1.8 split agreed: M1.8a seat map API; M1.8b login/register UI; M1.8c seat map page, hold,
  checkout, wallet, Playwright (local only), 1000-round proof. Decisions: layout and status split,
  both public; qrcode-generator 2.0.4; Playwright 1.64.1 locally, CI job considered in M7.
- New in `inventory.catalog`: `SeatMapController`, `SeatMapQueries`, `SeatMapViews`.
  `GET /events/{id}/layout` (venue from `layout_json` + seats ORDER BY seat_id, i.e. byte-wise uuid
  order) and `GET /events/{id}/seatmap` (`{eventId, seatCount, available, asOf}`: 1 bit per seat,
  MSB first, base64; one statement). 404 for unknown/DRAFT, 400 malformed id, 500 for a broken layout.
- SecurityConfig permits exactly `GET /events/*/layout` and `/events/*/seatmap` (not `/events/**`).
- Spec: two paths, seven schemas (`LayoutRect`/`LayoutPoint` prefixed), additive only; schema.d.ts
  regenerated.
- Frontend: Vite proxy adds /holds, /orders, /me, /tickets; `fetchLayout`/`fetchSeatMap`/`useLayout`/
  `useSeatMap`; `seatmap/availability.ts` decoder with tests.
- Docs: D-020 (draft; no `seq` until M5.1 = PRD 10 deviation), PRD 10 table, venue-format.md
  "Over the API", PLAN notes on M1.8, M2.4, M5.1, M7. No new dependency.

**Verified**
- `./gradlew test --rerun`: 417 tests, 0 failures/errors/skipped (9 min 25 s); SeatMapApiTest 9/9,
  OpenApiSpecTest 9/9, ModularityTest green.
- Frontend: typecheck, lint, test (40), check:api, build green.

**Next**: M1.8b login and register screens, session restore on load, RequireAuth.

**Open problems**
- D-020 is a draft for me to rewrite in my own words.
- Layout payload size is an estimate (~240 KB raw); measure in M2.4.
- The seat map can be polled anonymously without limits until M4 (rate limits, admission).
- No correctness-reviewer pass (read-only endpoints, no locking code).
- Still open from M1.7: the 1000-round proof (planned at the end of M1.8c).

## 2026-11-12 - M1.8b Login and register screens, session restore, RequireAuth

**What changed**
- `api/auth.ts`: session status (`restoring` | `loggedIn` | `loggedOut`) that follows the in-memory
  token; `restoreSession()` (one memoized refresh per page load, any failure = logged out, no
  error shown); `register()`; `resetSession()` for tests. Login/register/logout now turn an
  unreachable server into NETWORK_ERROR (login used to throw a raw TypeError).
- `auth/`: `useSession` (useSyncExternalStore), `RequireAuth` layout route (waits while restoring,
  else `/login?next=`), `safeNext` (in-app paths only: no `//`, `\`, control characters, other
  origins, /login or /register).
- Pages: /login, /register (register, then login with the same credentials; if only the login
  fails, /login with "Account created. Log in to continue."). Labels, autocomplete
  email/current-password/new-password, password rule shown up front, field errors via
  aria-describedby + aria-invalid + focus, form errors in an always-present role="alert" region.
  Error text by code in `pages/authErrorText.ts`; field messages use the server's wording.
- Header: Log in (with next) / Log out. Logout clears the token and the TanStack Query cache even
  if the request fails, and warns the session may come back on reload.
- Tests: MSW server's default `POST /auth/refresh` = INVALID_REFRESH_TOKEN; `renderAt` returns the
  router and takes a route list. New: LoginPage (5), RegisterPage (5), session (12), safeNext.
- Docs: PLAN M1.8b note + M1.8c note (logout must also clear the sessionStorage hold and
  last-order entries, even when POST /auth/logout fails); D-011 "Session" item; M1.8a note
  corrected to committed (f060b65). No new dependency.

**Verified**
- `./gradlew test --rerun`: 417 tests, 0 failures/errors/skipped (8 min 32 s).
- Frontend: test 79/79 (5 consecutive green runs), typecheck, lint, check:api, build green.
- Mutation checks: making `safeNext` follow any next fails 14 tests; removing the shared refresh
  fails the "two 401s, one refresh" UI test.

**Next**: M1.8c seat map page, hold, checkout, wallet (under RequireAuth), Playwright purchase
test locally, the 1000-round proof.

**Open problems**
- Not tried in a real browser yet (only jsdom); M1.8c's Playwright test will cover login.
- The header shows no account email (no `GET /me` for the user).
- Two test flakes this session came from requests outliving their test; tests that end on a page
  that fetches must wait for it to load.
- Still open: D-020 draft to rewrite; the 1000-round proof (end of M1.8c).

## 2026-11-12 - M1.8c part 1 Seat map page: canvas, picking, hold, countdown, release

**What changed**
- /drops/:id/seats under RequireAuth; "Pick seats" on the event page (SCHEDULED/LIVE).
- Canvas2D venue: `seatmap/geometry.ts` (fit, inverse, nearest seat, 8 CSS px minimum hit radius,
  pure and tested), `draw.ts` (outlines, stage, seats free/taken/yours; device pixels for
  high-DPI), `SeatCanvas.tsx` (ResizeObserver width, DPR change listener, click → seat).
- Keyboard picker (row → free seat → Add) and a selected-seats list with prices and total;
  cap = min(perUserLimit, 20). Picked seats a newer seat map shows taken drop out (computed).
- `api/holds.ts`: createHold (with clock offset from serverTime), releaseHold. Countdown follows
  the server clock; timer clears the hold at expiry; "Continue to checkout" is a disabled stub.
- `booking/holdStore.ts`: hold in sessionStorage (`stampede:hold:<eventId>`); `clearBookingState()`
  on logout (even if the request fails) and inside `login()` before the token is set (covers
  register; a refresh/reload keeps it; a failed login keeps it).
- `ApiError` carries `seatIds`, `takenAgoMs`, `limit`. `pages/holdErrorText.ts`: every hold and
  checkout code in plain words. Every hold attempt refetches the seat map.
- Tests: setup stubs canvas `getContext` (null) and clears sessionStorage; LAYOUT/seatMapOf fixtures.

**Verified**
- `./gradlew test --rerun`: 417 tests, 0 failures/errors/skipped (11 min 18 s).
- Frontend: 131 tests (4 consecutive green runs), typecheck, lint, check:api, build green;
  bundle 120.55 KB gzipped.
- Mutation checks: removing logout/login clearing, the serverTime offset, the cap, or the refetch
  each fails the matching tests.

**Next**: M1.8c part 2: checkout (POST /orders), wallet with QR, Playwright purchase test locally,
the 1000-round proof.

**Open problems**
- The canvas hasn't been seen in a real browser yet (jsdom only fakes its size).
- Full redraw and linear hit test won't scale to 20,000 seats (M5).
- docs/DEVELOPING.md line 157 lists the old proxy prefixes (missing /holds, /orders, /me, /tickets).
- Still open: D-020 draft to rewrite; the 1000-round proof.

## 2026-11-12 - M1.8c part 2 Checkout, wallet with QR, Playwright purchase test; M1 exit met

**What changed**
- Checkout on the seats page: "Continue to checkout" opens a receipt (`pages/CheckoutReceipt.tsx`,
  mono, dotted leaders); Pay sends POST /orders (`api/orders.ts`). 201/200 PAID or PENDING: hold
  cleared, last order saved, go to /wallet. 410, 402, 404 and a 200 CANCELLED/REFUNDED order: hold
  cleared, seat map refetched. 409 SEAT_TAKEN and network errors keep the hold; Pay becomes Retry.
  `pages/checkoutErrorText.ts` handles SEAT_TAKEN, PAYMENT_FAILED, NETWORK_ERROR and delegates the
  rest to `holdErrorText`.
- `booking/lastOrderStore.ts` (`stampede:last-order`, cleared by `clearBookingState()` on logout and
  login); `holdStore.ts` exports the shared `stampede:` prefix.
- /wallet under RequireAuth, header link when logged in: GET /me/tickets grouped by order
  (`booking/groupTickets.ts`), seat label snapshot, QR as SVG (`qr/qrMatrix.ts`, `qr/TicketQr.tsx`:
  byte mode, level M, auto version, quiet zone 4). "Tickets on the way" with Retry when the last
  order has fewer tickets than items.
- Playwright: `frontend/e2e/purchase.spec.ts` (register, canvas click at a free seat via the app's
  `fitTransform`, hold, pay, wallet QR with 57 modules), `playwright.config.ts` (Chromium,
  webServer `npm run dev`), `tsconfig.e2e.json` (type-checked by `tsc -b`), `npm run e2e`. Vitest
  limited to `src/**/*.test.{ts,tsx}`. `CANVAS_PADDING` moved into `seatmap/geometry.ts`.
- New dependencies (exact, `--before=2026-10-28`): qrcode-generator 2.0.4, @playwright/test 1.64.1.
- Docs: DEVELOPING.md and frontend/README.md proxy lists fixed; Playwright commands documented.
- Frontend code committed by the user in 258c491; docs in the proposed docs commit.

**Verified**
- Frontend: typecheck, lint, 153 tests (was 131), check:api, build green; JS 129.7 KB gzipped.
- A real 195-character ticket string gives a 57×57 QR grid (level L would give 53×53).
- Playwright purchase test against the compose stack (`seed,demo-open-sale`): passed twice in the
  session (12.9 s, 5.9 s) and once headed by the user (10.0 s). Manual browser purchase by the user;
  a seat can't be sold twice.
- 1000-round proof (run by the user): BUILD SUCCESSFUL in 22 min 47 s; all six PESSIMISTIC harness
  scenarios 1000/1000 clean, 0 invariant violations, 0 exceptions. M1 exit criterion met.

**Next**: M2.1 optimistic strategy.

**Open problems**
- The 1000-round proof took 22 min 47 s, about twice the "about 11 min" in CLAUDE.md and
  DEVELOPING.md; update those numbers.
- The first proof attempt inside the Claude session was stopped for low system memory: run the full
  proof outside the session, with other apps closed.
- Playwright purchase test isn't in CI (M7.3 note). Each run sells one seat for good; reset the data
  with `docker compose down -v` long before the 2,014 demo seats run out.
- Still open: D-020 draft to rewrite.

## 2026-11-13 - M2.1 Optimistic inventory strategy (D-021)

**What changed**
- `OptimisticInventoryStrategy`: unlocked read `ORDER BY seat_id` (with the owner's `created_at` and
  `clock_timestamp()` for `takenAgoMs` in the same statement), `EventSeat` decides, hold row insert,
  one `UPDATE … WHERE version = :read` per seat in the read's order (= the lock order of D-015),
  allowance upsert last. Zero rows = `VersionConflict`, the try rolls back. `hold()` is
  `@Transactional(NEVER)`; each try is its own `TransactionTemplate` transaction; full-jitter backoff
  between tries with no connection held. 3 tries, 5 ms base, 50 ms cap (`stampede.inventory.optimistic.*`).
  HELD/SOLD is never retried.
- Release and sell extracted unchanged from the pessimistic strategy into `HoldClosing`; shared seat
  SQL into `SeatRows`; both strategies delegate. `InventoryStrategyConfig` binds the retry properties.
- `HoldRejection.CONTENDED` → `HoldResult.Contended` → 409 `SEAT_CONTENDED` with `seatIds` and
  `Retry-After: 1` (from `ErrorCode.retryAfterSeconds`, also documented in the spec).
- `HoldMetrics`: `stampede.inventory.hold.attempts{strategy}`, `.conflicts{strategy}`,
  `.duration{strategy,outcome}`; recorded by both strategies; tags never carry ids.
- Tests: `PessimisticInventoryStrategyTest` split into `InventoryStrategyContractTest` (43 shared tests)
  + Pessimistic (44 = 43 + kind) and Optimistic (52 = 43 + 9: conflict detected and retried, retry
  succeeds, taken seat not retried, CONTENDED with nothing written, interleaved opposite-order holds
  without deadlock, read takes no row lock, refuses a caller's transaction, meter tags, kind).
  Harness and cap tests are abstract bases with one subclass per strategy. `HoldApiTest` +2
  (SEAT_CONTENDED over HTTP; optimistic event holds). Shared `StrategyTestConfig`.
- Frontend: `SEAT_CONTENDED` message, selection kept, "Try again" button (seats page); +4 tests.
- Docs: D-021 (DRAFT), PRD 10 (SEAT_CONTENDED), `openapi.json` + `schema.d.ts`, DEVELOPING.md class
  names, PLAN note on M2.3 (test containers, background schedulers in tests).
- Committed by the user: be00b20 (the first 5 new main files; doesn't build on its own) and 2f75866
  (everything else, which completes it). This entry and the PLAN tick are a separate docs commit.

**Verified**
- Test counts before/after the split: PessimisticInventoryStrategyTest 44 → 44; Pessimistic harness 4
  and cap 2 unchanged per strategy; Optimistic unit 52.
- Deadlock mutation (writes in request order): the interleaving test failed with
  `ERROR: deadlock detected` (40P01, two processes waiting on each other's transaction); reverted,
  green again, no mutation in the code or in be00b20.
- In session: Optimistic/Pessimistic unit tests and HoldApiTest green; frontend typecheck, lint,
  157 tests, check:api, build all exit 0.
- By the user (PowerShell, other apps closed): `*ConcurrencyTest` + ModularityTest + OpenApiSpecTest
  BUILD SUCCESSFUL in 6m 27s; full backend suite --rerun (100 rounds) BUILD SUCCESSFUL in 15m 20s.
  The in-session full run had been stopped for low system memory.
- 1000-round proof of the optimistic strategy (user): `.\gradlew.bat test --rerun --tests
  "Optimistic*ConcurrencyTest" "-Dstampede.harness.rounds=1000"` BUILD SUCCESSFUL in 18m 11s.

**Next**: test-infrastructure cleanup (the M2.1 note on M2.3, pulled forward), then M2.2 Redis-first.

**Open problems**
- At JVM shutdown, `HoldExpirySweeper` in a cached test context logged "Connection refused" /
  "Pool is empty" after its database stopped (PLAN note on M2.3).
- `compileJava` prints an "unchecked or unsafe operations" note for `OpenApiConfig`; not checked
  whether it predates M2.1.
- D-021 is a draft for you to rewrite in your own words (also still: D-020).
- CLAUDE.md and DEVELOPING.md still say the 1000-round proof takes about 11 min. Measured: 22 min 47 s
  for the pessimistic scenarios (M1), 18 min 11 s for the optimistic ones alone, so `*ConcurrencyTest`
  at 1000 rounds now takes roughly 40 min. Update those numbers.

## 2026-11-13 - Test infrastructure cleanup (M2.1 note on M2.3, done before M2.2)

**What changed**
- `testsupport.TestDatabases`: one shared Postgres and one shared Redis container for the whole test
  JVM, and a fresh Postgres database per Spring test context (same isolation as a container each).
  All Testcontainers test bases and `TestcontainersConfiguration` use it; `SecretsAtRestTest` runs
  `pg_dump` through it.
- Test `application.properties`: hold expiry sweeper and invariant monitor schedules off in every test
  context; Hikari `minimum-idle=0`, `idle-timeout=10000` (ms; Hikari's setter takes a long, so `10s`
  failed to bind) so idle cached contexts release their connections. Harness bases set minimum-idle
  back to their pool size.
- Docs: PLAN notes on M2.2 (Redis keys must be isolated per test context once the Redis strategy
  writes them) and M2.3; 1000-round timing (~40 min) in CLAUDE.md and DEVELOPING.md; DECISIONS.
- Committed by the user: 817e616, 672259c, d2288d7, d0576a6. This entry and the PLAN result are a
  separate docs commit.

**Verified**
- Measured per batch: 2 containers (was up to 21 per full run), 15 contexts (was 16).
- By the user: full backend suite --rerun BUILD SUCCESSFUL in 13m 53s (was 15m 20s); the
  "Connection refused" / "Pool is empty" shutdown errors are gone. `CheckoutFailureTest` run alone 3
  times: all passed. An earlier full run that failed in `CheckoutFailureTest` and hung overlapped
  with in-session batch runs and edits, so it is treated as invalid.
- `spring.test.context.cache.maxSize=8` not needed (PLAN updated).

**Next**: M2.2 Redis-first strategy (isolate Redis keys per test context first; see the PLAN note).

**Open problems**
- The invalid `CheckoutFailureTest` failure was not reproduced; if it shows up again in a clean full
  run, investigate it as possible flakiness rather than interference.
- `compileJava` "unchecked or unsafe operations" note for `OpenApiConfig`: still not checked.
- D-021 and D-020 are drafts for you to rewrite in your own words.

## 2026-11-13 - M2.2a Redis hold store (first of three M2.2 sub-tasks)

**What changed**
- M2.2 split into M2.2a (Redis alone), M2.2b (persistence, reconciler) and M2.2c (strategy bean, sell,
  invariants, harness). Decisions in D-022 (draft): Redis Stream + consumer; on disagreement Postgres
  wins and Redis-only live holds roll forward; invariant 5 tolerates 2 s at runtime (zero in tests
  after draining the stream) and compares by time, not by status.
- `redis/hold.lua` and `redis/release.lua`: all checks before any write; every key of a hold expires
  at the same millisecond by Redis' clock; the per-user cap is recounted from the user's live holds
  (a change to D-005's counter key); one HELD / RELEASED stream entry per change.
- `inventory.strategy`: `RedisHoldStore`, `RedisKeys`, `RedisInventoryProperties`
  (`stampede.inventory.redis.key-prefix`). Not an `InventoryStrategy` bean: `forKind("REDIS_FIRST")`
  still throws.
- Test isolation: a Redis key prefix per Spring test context (`TestDatabases.registerRedis`,
  `app_keys_N` in `TestcontainersConfiguration`), cleanup by prefix with `TestRedis.deleteKeys`.
- CLAUDE.md: a targeted test run must include every test class whose Spring context loads a changed
  package.
- Committed by the user: 0738886, 5161aed, d62b56a. This entry, the PLAN tick and the CLAUDE.md rule
  are a separate docs commit.

**Verified**
- In session: `RedisHoldStoreTest` (25), `RedisHoldStoreConcurrencyTest` (3 scenarios x 100 rounds),
  `RedisKeyIsolationTest` (6), `RedisKeyPrefixInAppContextTest` (1), ModularityTest.
- Regression: the user's first full run failed (512 tests, 6 failed, all `HoldExpirySweeperTest` at
  context load). Its `@JdbcTest` slice component-scans `inventory.strategy`, found `RedisHoldStore`
  and had no `StringRedisTemplate`. The first targeted run had not included it. Fixed in the test's
  setup (Redis auto-configuration + its own key prefix), no assertion changed; sweeper, both strategy
  contract tests, the four Postgres concurrency classes and the Redis tests then passed (7m 40s).
- By the user: full suite --rerun BUILD SUCCESSFUL in 13m 11s after the fix;
  `RedisHoldStoreConcurrencyTest` at 1000 rounds BUILD SUCCESSFUL in 5m 47s.

**Next**: M2.2b (event loader, stream consumer to Postgres through `EventSeat`, expiry through the
sweeper, reconciler, explicit AOF flags in compose). Notes are under M2.2b and M2.2c in PLAN.md.

**Open problems**
- 0738886 on its own has one failing test (`everyKeyOfAHoldExpiresAtTheSameMillisecond`, a test
  helper that Lettuce could not run); 5161aed fixes it. History left as it is.
- M2.2c needs approval of the contract-test split (behaviour contract for all three strategies,
  Postgres-locking contract for two) before any test is moved, and a threshold for the stuck-sweeper
  rule.
- Single Redis node assumed by the scripts (D-022 known limits).
- D-022, D-021 and D-020 are drafts for you to rewrite in your own words.
- `compileJava` "unchecked or unsafe operations" note for `OpenApiConfig`: still not checked.

## 2026-11-13 - M2.2b Redis persistence and reconciler (second of three M2.2 sub-tasks)

**Changed**
- Decisions (user): a consumer on every instance (one group, unique names); a dead-letter stream after
  5 non-transient failures, with the reconciler repairing the state; reconciler every 30 s; a cap
  disagreement is a Postgres-wins conflict; `release.lua`'s RELEASED entry gains `createdMs` and
  `expiresMs` (fields and assertions only added).
- `RedisEventLoader` + `redis/load.lua`: loads an event from one REPEATABLE READ snapshot in one
  atomic script, `meta` (with a `loadedMs` epoch) last, and writes nothing if the event is loaded.
- `RedisStreamConsumer` + `RedisConsumerLifecycle`: XREADGROUP with BLOCK 1 s, XACK after commit,
  XPENDING + XCLAIM of idle entries, dead letters, XTRIM MINID, group recreated after NOGROUP.
- `RedisHoldWriter`: idempotent apply with the `holds` row as the gate (ON CONFLICT DO NOTHING,
  tombstones for holds already over in Redis), D-015 lock order, closing older Redis-gone holds in
  their own transactions, Postgres wins otherwise; `expire()` for the M2.2c strategy.
- `RedisReconciler` + schedule: rules R0 load, R1 roll forward, R2 close (epoch-guarded), R3 SOLD
  markers and cap members, R4 release in Redis.
- `HoldClosing.closeAsRedisDecided`, `SeatRows.addToAllowance(event, user, count)`, `RedisHoldStore`
  load/epoch/time/scan/sold-marker methods, `RedisKeys` group and dead-letter keys, nested consumer
  and reconciler properties (off in tests), lag/age gauges and counters, compose `appendfsync
  everysec` + `maxmemory-policy noeviction`.
- CLAUDE.md and memory: a targeted run needs only one test class per distinct application context.
- D-022 M2.2b section drafted. Committed mid-task by the user (3aab3dd..ee96417); this session-end
  commit completes them.

**Verified**
- Tests: `RedisStreamConsumerTest` (11), `RedisHoldWriterTest` (10), `RedisReconcilerTest` (8),
  `RedisEventLoaderTest` (4), `RedisPersistenceConcurrencyTest` (100 rounds; reached 1,224 duplicate
  HELD, 1,165 tombstoned HELD, 2,263 duplicate RELEASED, 155 R2 closes, zero conflicts).
- Targeted run: 281 tests, 6 failed, all `HoldExpirySweeperTest` at context load (its scan of
  `inventory.strategy` found a second `meterRegistry` bean in the new test base). Fixed in the new
  base only (reuses `StrategyTestConfig`); rerun of the sweeper test, `Redis*Test`,
  `*InventoryStrategyTest`, ModularityTest: BUILD SUCCESSFUL in 4m 48s.
- Bug found while testing: after NOGROUP, `pollOnce` recreated the group without reading; now reads.
- By the user: full suite with --rerun BUILD SUCCESSFUL in 15m 28s.

**Next**: M2.2c (strategy bean, sell, invariant 5 and the stuck-sweeper rule, contract tests,
harness). Notes are under M2.2c in PLAN.md.

**Open problems**
- The consumer's pre-close step checks "loaded", not the load epoch; the R2 epoch guard has no test.
  Both are M2.2c notes and D-022 known limits.
- `sell` (M2.2c) must delete the Redis hold hash in its script, or R4 un-counts bought seats.
- A released-but-unapplied hold closed after its expiry is recorded EXPIRED; a reused hold id is only
  logged (D-022 known limits).
- D-022 is a draft for you to rewrite in your own words.
