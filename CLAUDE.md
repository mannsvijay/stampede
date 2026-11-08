# Stampede

Ticketing platform built for contention: thousands of buyers racing for the same seats.
Full spec: docs/PRD.md (read the relevant section when a task touches it; don't load it all).
Current plan and progress: docs/PLAN.md. Design decisions: docs/DECISIONS.md.

## Who you're working with
I'm a final-year engineering student building this to learn and to explain it in interviews.
When you introduce a non-obvious concept (a locking pattern, a Redis primitive, a Spring feature),
explain it in 2-3 plain sentences in your summary. Prefer clear code over clever code.

## Commands
<!-- Update this section at the end of M0 with the real commands. -->
- Start everything: `docker compose up -d`
- Backend tests: `cd backend && ./gradlew test`
- One test class: `cd backend && ./gradlew test --tests "ClassName"`
- Frontend dev: `cd frontend && npm run dev`
- Frontend checks: `cd frontend && npm run typecheck && npm run lint`
- Load test: `./scripts/k6.sh <scenario>` (k6 runs in Docker)

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
