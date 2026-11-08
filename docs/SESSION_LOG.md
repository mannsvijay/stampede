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
