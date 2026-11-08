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
