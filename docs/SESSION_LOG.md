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

**Verified**: `./gradlew test` green; compose all healthy; live `/actuator/health` UP; stopping Redis
-> 503 DOWN in ~2 s, recovers on restart; user re-verified end to end with the real `.env`.

**Next**: M0.2 Flyway V1 schema (PRD 9).

**Open problems / notes**
- Backend not yet in compose (added as M0.10).
- Actuator `show-details: always` is dev-only; restrict before deploy (noted on M7.1).
- Windows: stopping `./gradlew bootRun` can leave the app JVM holding port 8080; kill it by PID.
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

**Verified**: `./gradlew test --rerun` green: ApplicationHealthTest 1/1, SchemaMigrationTest 13/13.

**Next**: M0.3 Venue JSON format + generator script + seed loader.

**Open problems / notes** (all recorded on their PLAN.md tasks)
- orders has no hold_id: decide before M1.5.
- Duplicate seat IDs in a hold must be rejected in code (M1.4).
- Seat venue must match event venue: M0.3 loader + M1.7 invariant check.
- POST /checkins idempotency via scanner-generated checkins.id (offline scanner stretch).
- `./gradlew test` can report UP-TO-DATE without running anything; use `--rerun` for evidence.
