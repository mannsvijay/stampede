# Stampede

Ticketing platform built for contention: thousands of buyers racing for the same seats.
Full spec: docs/PRD.md (read the relevant section when a task touches it; don't load it all).
Current plan and progress: docs/PLAN.md. Design decisions: docs/DECISIONS.md.

## Who you're working with
I'm a final-year engineering student building this to learn and to explain it in interviews.
When you introduce a non-obvious concept (a locking pattern, a Redis primitive, a Spring feature),
explain it in 2-3 plain sentences in your summary. Prefer clear code over clever code.

## Commands
Git Bash forms, run from the repo root. PowerShell forms for me: docs/DEVELOPING.md.
- Infra (Postgres, Redis, Mailpit): `docker compose up -d`; stop: `docker compose down`.
  Reset data: `docker compose down -v` deletes the volumes. Ask me first, then `up -d` and seed again.
- Backend tests: `cd backend && ./gradlew test --rerun`
- One test class: `cd backend && ./gradlew test --rerun --tests "ClassName"`
- Run the app: `cd backend && ./gradlew bootRun --args='--spring.profiles.active=seed,demo-reschedule'`
  Profiles, comma-separated: `seed` (load the demo venue and event), `demo-reschedule` (move the demo
  drop into the future), `openapi` (serve /v3/api-docs), `bootstrap-admin` (create the first admin
  from .env). Seed once and exit: add ` --spring.main.web-application-type=none` inside the quotes.
- Regenerate the demo venue file: `cd backend && ./gradlew generateVenue`
- Update docs/openapi.json after an intended API change:
  `cd backend && ./gradlew test --rerun --tests "OpenApiSpecTest"`, review the diff, then
  `cp backend/build/openapi/openapi.json docs/openapi.json` and `cd frontend && npm run gen:api`.
- Frontend install (as CI does): `cd frontend && npm ci --ignore-scripts`. Adding or upgrading
  packages: ask me first; exact versions, `npm install --before=<date>` (D-011).
- Frontend: `cd frontend && npm run <script>`: `dev` (:5173, proxies the API to :8080), `typecheck`,
  `lint`, `check:api`, `gen:api`, `build`; tests: `npm test`. CI runs typecheck, lint, test,
  check:api, build.
- Lint the CI workflow (Docker):
  `MSYS_NO_PATHCONV=1 docker run --rm -v "$(pwd -W):/repo" -w /repo rhysd/actionlint@sha256:b1934ee5f1c509618f2508e6eb47ee0d3520686341fec936f3b79331f9315667`
- Load test (from M2.4): `./scripts/k6.sh <scenario>` (k6 runs in Docker)

## Gotchas
- Gradle can report `test UP-TO-DATE` and run nothing. Always use `--rerun` when showing evidence.
- Testcontainers tests and actionlint need Docker Desktop running.
- Windows: stopping bootRun can leave the app JVM on port 8080. Find it with
  `netstat -ano | grep ':8080 .*LISTENING'` and ask me before `taskkill //PID <pid> //F`.
- Never read, print or edit `.env` (secrets). `.env.example` is the template and is fine to read.
- Git Bash rewrites paths in Docker `-v` arguments; use `MSYS_NO_PATHCONV=1` and `$(pwd -W)`.

## Architecture rules
- Modular monolith: Spring Boot 3, Java 21, Spring Modulith. Modules: identity, inventory, booking,
  payments, ticketing, waitingroom, realtime, control. Cross-module calls only through each module's
  public API package or events. The Modulith verification test must stay green.
- Data access: Spring JDBC (JdbcClient) + Flyway. No JPA/Hibernate. Locking must be visible in SQL.
- PostgreSQL is the source of truth. Redis is the fast path and is never trusted alone.
- Money is `long` paise. Timestamps are UTC (`Instant`, `timestamptz`).
- Errors use RFC 7807 problem+json with the stable codes from PRD section 10.
- Never add a dependency without asking me first.

## Correctness rules (IMPORTANT)
- Never weaken, skip, or delete a test or an invariant check to make something pass. If a test is
  wrong, stop and tell me why.
- Every state transition goes through the state machine guard; invalid transitions are rejected.
- Multi-seat holds are all-or-nothing in every inventory strategy.
- The per-user ticket cap is enforced server-side under concurrency, in every strategy (including Lua).
- The unique partial index on order_items(event_id, seat_id) WHERE active is the last line of defence.
  Never drop or relax it.

## Workflow
- Multi-file changes: plan first, wait for my approval.
- Keep each change scoped to the current task in docs/PLAN.md. Note unrelated issues; don't fix them.
- Write or update tests with every change. Concurrency code gets a concurrency test.
- Show evidence when done: the commands you ran and their output.
- Don't push to remote. I review and push myself.
- When compacting, preserve: current task ID, files modified, test commands, open problems.
