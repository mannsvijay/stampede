# Stampede build plan

This plan overrides the milestones in PRD section 14. Work top to bottom. One task per Claude session.
Tick a task only when its tests pass and it's committed.

## Changes from PRD v1.0
1. **Re-tiered MVP.** MVP = M0 to M3. That alone is a complete, shippable project: correct booking
   under contention, three benchmarked strategies, idempotent checkout with a saga. Everything after
   M3 is v1; the stretch list is last.
2. **No Kafka until stretch.** Outbox relay publishes to Redis pub/sub behind an `EventPublisher`
   interface. Kafka (Redpanda) becomes a drop-in second implementation later.
3. **Realtime path is Redis pub/sub only**, with a written latency budget (target p95 < 300 ms):
   hold commit -> publish (<10 ms) -> instance receives (<10 ms) -> 100 ms diff batch -> WebSocket
   send -> client paint. The outbox is for durable domain events, not the seat-map fast path.
4. **Offline ticket scanning needs a revocation sync.** Before gates open, scanners download the
   latest `ticketId -> version` map. Scans of stale versions are rejected; remaining offline-window
   risk (two offline gates) is documented, and duplicates are flagged on sync.
5. **Waiting-room fairness.** Everyone who arrives before T-0 is shuffled randomly (lottery); FIFO
   applies after T-0. "Arrival" = server receipt of the queue-join request. PoW difficulty is kept
   low enough for a low-end Android phone (measure it). Kendall tau is computed for post-T-0 joins.
6. **Invariant 5 (Redis vs Postgres)** has a tolerance window (e.g. 2 s) under the Redis-first
   strategy, since that strategy is eventually consistent by design.
7. **Bot metric honesty.** The README states the bots were written by the author and what they do.

## M0 - Skeleton (week 1)
- [x] M0.1 Monorepo layout, docker-compose (postgres 16, redis 7, mailpit), Gradle backend that boots
- [x] M0.2 Flyway V1: full schema from PRD 9 with CHECK constraints and the unique partial index
      Decisions: UUID v4 ids (app `UUID.randomUUID()`, `gen_random_uuid()` default); `holds.seat_ids uuid[]`
      with `event_seats.hold_id` as the authoritative owner; event_seats status AVAILABLE/HELD/SOLD only
      (CHECKED_IN lives on tickets; transfer = version bump); transition guard in code only (M1.1), plus a
      CHECK tying seat status to hold_id/order_id; audit_log append-only via triggers; added
      `user_event_allowance`, `tickets.issued_at`, composite same-event FKs, extra partial unique indexes.
- [x] M0.3 Venue JSON format + generator script + seed loader (one ~2,000-seat venue)
      Note (from M0.2): the loader must only create event_seats whose seat belongs to the event's venue
      (seat -> section -> venue = event.venue_id); the schema can't enforce this with a FK.
      Decisions: venue format in docs/venue-format.md; Java generator (`./gradlew generateVenue`) with a
      drift test against the committed JSON; loader runs under Spring profile `seed` (ApplicationRunner,
      app keeps serving); name-based UUID v3 ids for all seed rows + ON CONFLICT DO NOTHING; a changed
      seed file for already-loaded data fails loudly and rolls back.
- [x] M0.4 Spring Modulith module packages + verification test
      Decisions (D-007): Spring Modulith 1.4.14 (BOM + spring-modulith-api + starter-test); each module
      exposes `api` (may call) and `events` (may listen) subpackages as named interfaces; the full
      allowed-dependency table is enforced from day one via `allowedDependencies`; no notifications
      module; no module diagrams yet; no event publication registry (outbox is M3.2).
- [x] M0.5 Auth: register, login, refresh rotation, hashed refresh tokens, JWT filter
      (includes Flyway V2: `users.role` with a CHECK constraint; deferred from M0.2)
      Note (from M0.4): decide how other modules get the current user: an `identity::api` type (only
      waitingroom and ticketing may depend on identity today) vs a shared module every module may use.
      Decide together with the shared-module question in M0.6 (problem+json error codes, money).
      Decisions (D-008, D-009): Spring OAuth2 resource server, HS256 key from STAMPEDE_JWT_SECRET;
      bcrypt cost 12 (72-byte cap); refresh token in an httpOnly SameSite=Strict cookie on /auth with a
      required custom header; 15 min access / 7 day refresh / 30 day family cap; strict reuse
      detection; `shared` module (CurrentUser, error codes); 401/403/auth errors as problem+json;
      first ADMIN via the `bootstrap-admin` profile. V2 adds `refresh_token_families` (one lock row
      per login session; fixes a revocation deadlock and a logout race found by the concurrency tests).
      Open: nothing deletes expired refresh token families yet; add a cleanup job (e.g. with the M1.4
      sweeper) before the table matters. Still open after M1.4 (not in its scope).
      Correctness review (RefreshTokenStore): no bug in the code; fixed three test/edge findings
      (a one-refresh-vs-one-logout race test, proven to fail with logout disabled; a tighter
      family-cap assertion; rotate now checks the family's own expiry) plus hardening (guarded
      consume with a row-count check; READ COMMITTED requirement documented). lock_timeout deferred
      to M4.3.
- [x] M0.6 GET /events, GET /events/{id}; RFC 7807 error handler
      Note (from M0.4): where shared code lives (error codes, problem+json types) is decided with the
      current-user question in M0.5.
      Note (from M0.3): the demo event's drop_at/starts_at are set relative to now() on first load and
      never move afterwards, so after a while the board shows a past drop. Needs a reset/refresh option.
      Decisions (D-010, draft): inventory owns the read endpoints (internal `inventory.catalog`,
      JdbcClient, detail read in one SQL statement); list is `{items, serverTime}`, capped at 100,
      no pagination yet; no availability counts (live demand comes from realtime, M5); DRAFT events
      are hidden (404). One problem+json handler in shared's internal `shared.web` package (D-009
      reworded), plus a problem+json `/error` controller; new codes EVENT_NOT_FOUND, NOT_FOUND,
      METHOD_NOT_ALLOWED, UNSUPPORTED_MEDIA_TYPE, REQUEST_FAILED, INTERNAL_ERROR. OpenAPI: springdoc
      2.10.0 (`-api`, no UI), generated spec committed at `docs/openapi.json` with a drift test;
      `/v3/api-docs` is off unless the `openapi` profile is active. Dev-only `demo-reschedule`
      profile moves the demo event's drop/start times into the future (SCHEDULED only).
- [x] M0.7 Frontend shell: Vite + React + TS, router, events list from the API, OpenAPI-generated client
      Note (from M0.5): funnel all refreshes through one shared in-flight call; strict reuse detection
      logs the user out if two tabs refresh with the same token at the same moment. Use the Vite dev
      proxy so the API is same-origin and the /auth refresh cookie is sent.
      Note (from M0.6): generate the client from the committed `docs/openapi.json` (not the live
      endpoint). The list response is `{items, serverTime}`; correct the board countdown with
      `serverTime`. Errors: branch on `code` in the problem+json body. `RegisterRequest.password` is
      not marked required in the spec (its custom @ValidPassword constraint isn't understood by
      springdoc); fix that in the spec when the register form needs it.
      Decisions (D-011, draft): npm; openapi-typescript + openapi-fetch with the generated
      `src/api/schema.d.ts` committed (`npm run gen:api`, `npm run check:api`); React Router;
      TanStack Query; CSS Modules + `tokens.css` (PRD 7 colours); Vitest + React Testing Library +
      jsdom + MSW (Playwright from M1.8). Pages at `/` and `/drops/:id`; the Vite proxy forwards only
      /auth, /events, /actuator and /v3. Access token in memory; on a 401 one shared refresh per tab,
      serialised across tabs with the Web Locks API; retry once. TypeScript pinned to 5.9.3
      (typescript-eslint and openapi-typescript don't support 7 yet). Exact versions, each published
      14+ days before install, installed with `npm install --before=<date>`. No login UI yet.
      Backend: documentation-only `@Schema` on RegisterRequest.password (required, minLength 12).
      Open: revisit the TypeScript 5.9.3 pin when typescript-eslint and openapi-typescript support 7.
      Open: `npm audit` reports 10 moderate findings, all one minimatch advisory (GHSA-g3hv-75wg-m249)
      in dev-only lint tooling (`npm audit --omit=dev`: 0). The fix (minimatch 11.0.1, published
      2026-10-27) passes the 14-day rule from 2026-11-10; it needs an npm `overrides` entry because
      eslint 10.13 asks for minimatch 10. Decided in M0.8: accepted for now (D-011, see the
      follow-up below).
- [ ] M0.7a minimatch override (its own commit, not before 2026-11-10T22:26Z, when 11.0.1 is 14 days
      old): add `"overrides": { "minimatch@10": "11.0.1" }` to frontend/package.json, install with
      `npm install --before=<date>`, confirm `npm audit` is clean and lint/test/build pass. Remove the
      override once ESLint itself requires minimatch 11 (10.14.0 still asks for `^10.2.6`).
- [x] M0.8 GitHub Actions: backend tests (Testcontainers) + frontend typecheck/lint
      Decisions (D-012, draft): one workflow `.github/workflows/ci.yml` on push to main, pull_request
      and workflow_dispatch; jobs Backend (Temurin 21, setup-gradle with wrapper validation and
      cache, wrapper warm-up retried 3x for the github.com 504, `./gradlew test`, reports uploaded on
      failure) and Frontend (Node from .nvmrc, `npm ci --ignore-scripts`, typecheck, lint, test,
      check:api, build, `npm audit --omit=dev --audit-level=high` blocking, full `npm audit`
      report-only). `contents: read`; superseded PR runs cancelled (main runs are not); actions
      pinned to commit SHAs, each release 14+ days old. `distributionSha256Sum` added to the Gradle
      wrapper. actionlint (Docker, pinned image) checks the workflow locally.
      Proof (2026-11-09): CI run #1 on main (2a4ece4) green for Backend and Frontend in 2m 32s;
      draft PR #1 (ci/prove-red, broken money test) failed Frontend and passed Backend; PR closed,
      branch deleted, main never had the broken commit.
      Not done: branch protection on main (see D-012).
- [x] M0.9 Update CLAUDE.md commands with the real ones
      Done: CLAUDE.md lists the Git Bash commands plus gotchas; docs/DEVELOPING.md has the PowerShell
      forms, `.env` setup and JWT secret generation; a short root README.md carries the CI badge.
      `.claude/settings.json`: allow `npm test`, `npm ci --ignore-scripts`, `npm audit` (two exact
      forms), `docker ps`, `git status *`, `git show *`; `ask` before `docker compose down -v`;
      deny `Read(**/.env)` and `Edit` on the .env files (no deny rule loosened).
- [x] M0.10 Backend Dockerfile + `backend` service in docker-compose (needed for the M0 exit criterion;
      found in M0.1, which runs the backend on the host via `./gradlew bootRun`)
      Decisions (D-013): clean clone = one step, copy `.env.example` to `.env` and fill in the
      secrets (compose's `:?` names anything missing); multi-stage Dockerfile, Temurin 21 JDK build
      and Temurin 21 JRE (noble) runtime, pinned by digest (14-day rule), non-root uid 10001;
      backend reaches postgres/redis by service name with internal ports fixed in compose; seeds
      with `seed,demo-reschedule` by default (`STAMPEDE_COMPOSE_PROFILES` overrides); healthcheck
      with the image's curl; frontend stays on `npm run dev`; CI `Compose smoke` job (plain
      `docker compose build`, throwaway `.env`, GET /events, non-root and no-.env checks).
      Verified locally (2026-11-09): image builds (6m 48s cold); `up --wait` all 4 healthy; /events
      returns the demo event; uid 10001; no `.env` in image or jar; 180 backend tests green;
      actionlint clean.
      Proof (2026-11-10): CI run #7 on main (05edf51) green for Backend, Frontend and Compose smoke.
**Exit:** on a clean clone, after copying `.env.example` to `.env` and filling in the secrets,
`docker compose up` runs the backend and infrastructure (Postgres, Redis, Mailpit) with the demo
data seeded; the frontend runs with `npm run dev` (it deploys to Vercel in M7.1); CI green.
**Exit met (2026-11-10):** CI run #7 (05edf51), all three jobs green; `Compose smoke` builds the
image on a fresh checkout with `.env` made from `.env.example`, brings every service to healthy
and gets the seeded demo event from GET /events. Still open in M0: M0.7a (date-gated override).

## M1 - Core booking, pessimistic (weeks 2-3)
- [x] M1.1 Seat state machine (domain class) + unit tests + property tests (jqwik)
- [x] M1.2 `InventoryStrategy` interface; pessimistic strategy (SELECT ... FOR UPDATE, sorted IDs).
      ~~Write this one yourself by hand; Claude reviews.~~ Changed during the task: Claude wrote the
      implementation (from the 13-step guide in the session), I reviewed it, plus the
      correctness-reviewer agent. The interface, types and tests came first.
      M1.2a (the `/explain-back M1.2` quiz) was dropped on 2026-11-10 by my choice.
- [x] M1.3 Concurrency test harness: N threads race for the same seats, exactly one wins, x1000
      Result 2026-11-10 (local, pessimistic, 16 threads, pool 20, the M1 exit evidence):
      singleSeat 1000/1000 in 71.7 s, overlappingSets 1000/1000 in 95.4 s, churn 1000/1000 in
      94.4 s (261.5 s total); 0 invariant violations, 0 exceptions. CI runs 100 rounds per scenario
      (`-Dstampede.harness.rounds=100`, about 26 s). Run: `PessimisticConcurrencyTest`; reports in
      `backend/build/reports/concurrency/`.
      Changed in M1.4: the default is now 100 rounds (as CI); the full proof passes
      `-Dstampede.harness.rounds=1000`. 1000-round times after M1.4 (allowance + I8 on every round):
      singleSeat 106.7 s, overlappingSets 132.5 s, churn 124.3 s, capRace 76.6 s;
      HoldCapConcurrencyTest 96.1 s + 125.8 s; 662 s in all.
- [x] M1.4 Holds: create (all-or-nothing, per-user cap), release, 5-min expiry sweeper
      Note (from M0.2): reject duplicate seat IDs in a hold request in code; `holds.seat_ids` has no
      DB-level uniqueness (a CHECK can't contain a subquery).
      Note (from M1.1, D-014): map `IllegalSeatTransitionException` to API errors here. Hold on a
      HELD/SOLD seat is SEAT_TAKEN (409, with `takenAgoMs`). Decide what DELETE /holds/{id} returns for
      a hold that already expired or isn't the owner, and HOLD_EXPIRED's HTTP status (PRD 10 names
      the code but no status).
      Note (from M1.2, D-015): keep the lock order. The allowance row is locked after the seats, in
      both hold and release. The sweeper releases one hold per transaction (find expired ids first,
      then call `release(…, EXPIRED)` for each), never several holds in one transaction. The sweeper
      and hold service must not be `@Transactional` themselves (the strategy's REQUIRED would join).
      Put the allowance UPDATE between the seat decision (step 4) and the hold insert (step 5), so
      LIMIT_EXCEEDED can be a `Rejected` with nothing written; add `LIMIT_EXCEEDED` to `HoldRejection`.
      `release(…, EXPIRED)` already returns `NotExpired` for a hold extended after it was picked.
      Decisions (D-016, draft): `inventory.api.HoldService` (not @Transactional) picks the strategy via
      `InventoryStrategies`, new holdId per attempt, 5 min holds; `inventory.holds` has the controller
      and the sweeper. Cap: one upsert on `user_event_allowance` as step 4b (precedence UNKNOWN_SEAT >
      SEAT_TAKEN > LIMIT_EXCEEDED), decrement as step 11b on every release reason; a drifted counter
      throws and rolls back. `takenAgoMs` from the owner's `created_at` (hold, or order if SOLD), read
      in the strategy's transaction on the rejection path. Sweeper: every instance, fixed delay 5 s,
      keyset pages of 500, one hold per transaction (`stampede.holds.sweeper.*`). Retry: SEAT_TAKEN on exactly
      the seats of the caller's own active hold returns that hold with 200. Statuses: SEAT_TAKEN,
      LIMIT_EXCEEDED, SALE_NOT_OPEN, HOLD_CONVERTED 409; HOLD_NOT_FOUND 404 (also for someone else's
      hold); DELETE of your own released/expired hold 204; unknown seat ids 400 VALIDATION_FAILED.
      HOLD_EXPIRED is 410, added to ErrorCode in M1.5 (the first task that returns it). Holds only
      after the drop while SCHEDULED/LIVE (SALE_NOT_OPEN); dev profile `demo-open-sale` opens the
      demo sale now. Harness: allowance reset, invariant I8 (allowance = seats of active holds, at
      most the limit), per-scenario limit (seat count for the M1.3 scenarios), `capRace`; plus
      `HoldCapConcurrencyTest` (disjoint seats, only the cap decides).
      Verified 2026-11-10: full suite 293 tests, 0 failures/errors/skipped (1000 harness rounds,
      17 min 33 s); frontend typecheck, lint, test (33), check:api, build green.
      Correctness review: no bugs found; fixed a two-sweeper test that could pass without overlap
      (now forced with a row lock) and sweeper starvation by broken holds (keyset paging). Low items
      recorded under D-016 "Known limits"; for M1.7: I8's `used > limit` check vs lowered limits.
- [x] M1.5 Orders from holds (happy path, synchronous fake payment for now)
      Note (from M0.2): `orders` has no `hold_id`. Decide before starting how an order links to its
      hold (the saga's HoldVerified step needs it), e.g. a V-next migration adding `orders.hold_id`.
      Note (from M1.1, D-014): decide when `event_seats.order_id` is set. The CHECK allows HELD with an
      order_id (attach at PENDING), but `EventSeat` has no such transition; it only sets order_id on
      `sell`, which clears hold_id. If orders attach while still HELD, add a guarded transition and
      tests, and decide what `sell` does when the seat already points to a different order (today
      it overwrites it). Sell on a seat no longer held by this hold maps to HOLD_EXPIRED.
      Note (from M1.4, D-016): HOLD_EXPIRED is 410 Gone; add it to `ErrorCode` here. Converting a hold
      (status CONVERTED) must NOT decrement the allowance: bought seats keep counting. Extend I8 to
      count the seats of the user's active orders too. Booking calls `release(…, SAGA_FAILED)`
      through inventory's api (`HoldService` has no such method yet; add one).
      Decisions (D-017, draft): V3 adds `orders.hold_id` NOT NULL + FK (hold_id, event_id) + UNIQUE.
      `booking.checkout.CheckoutService` (not @Transactional) runs one transaction per saga step:
      0 `HoldService.checkoutHold` (unlocked, prices from tiers), 1 `OrderWriter.createPending`
      (PENDING + active items; `uq_orders_hold` decides races), 2 `PaymentService.charge`
      (payments row before the provider call), 3 `HoldService.sellForOrder` -> new
      `InventoryStrategy.sell` (hold row, then seats ORDER BY seat_id; ACTIVE and `expires_at > now()`;
      hold CONVERTED; allowance untouched), 4 `OrderWriter.markPaid` (idempotent, 3 attempts).
      `event_seats.order_id` only on sell. No Idempotency-Key until M3.1 (PRD 10 deviation): a
      concurrent/repeated POST gets 200 with the existing order. `PaymentProvider` interface +
      `FakePaymentProvider` (always succeeds; tests spy it). Best-effort compensation after step 1:
      release the hold first (definitive under its lock; CONVERTED -> finish instead), then cancel
      the order (items inactive), then refund; failure table in D-017. New codes HOLD_EXPIRED 410,
      PAYMENT_FAILED 402, ORDER_NOT_FOUND 404; SEAT_TAKEN 409 for a seat in another order's active
      items. I8 counts ACTIVE + CONVERTED holds. GET /orders/{id} owner-only (404 otherwise).
      Correctness review: no invariant bug. Fixed: concurrency test now forces the overlap (barrier
      after step 0, counts 7 duplicate-key losers per round); an unknown payment outcome stays
      CREATED instead of FAILED; a decline whose compensation fails is 500, not 402; SEAT_TAKEN
      wording ("try again shortly"); guarded CONVERTED update; tests for "release throws -> nothing
      cancelled or refunded" and SEAT_TAKEN. Rest recorded under D-017 "Known limits".
      Also fixed (asked for separately): `JwtFilterTest.tamperedSignatureIs401` was flaky (~4/64).
      It flipped the last base64url char of the 32-byte signature, whose 2 low bits are padding, so
      A <-> B/C/D left the signature unchanged and the token still verified (failed once in the first
      M1.5 full run). It now flips a char in the middle of the signature and asserts the token changed;
      50/50 passes as a temporary @RepeatedTest(50).
      Verified 2026-11-10: full suite 326 tests, 0 failures/errors/skipped (100 harness rounds,
      5 min 30 s); 1000-round proof `*ConcurrencyTest`: 75 tests green, all six harness scenarios
      1000/1000 rounds clean, 0 invariant violations, 0 exceptions (9 min 40 s; OrderConcurrencyTest
      runs its own fixed 10 forced-overlap rounds); frontend typecheck, lint, test (33), check:api,
      build green.
- [x] M1.6 Ticket issuing: Ed25519 signing, QR payload, GET /me/tickets
      D-018 (draft): JDK Ed25519 from a 32-byte seed (`STAMPEDE_TICKET_SIGNING_KEY`, app won't start
      without a valid one); compact 81-byte payload + signature, 195-char QR string, spec and test
      vector in docs/ticket-format.md; saga step 5 after PAID, 3 attempts, on failure PAID/SEATS_SOLD
      and 201, a retried POST /orders issues them; idempotent via `uq_tickets_live_seat` + ON CONFLICT;
      holder = user id; V4 adds key_id and a seat-label snapshot; GET /me/tickets, public
      GET /tickets/keys (JWKS). No new dependency.
      Verified 2026-11-10: full suite 364 tests, 0 failures/errors/skipped (6 min 14 s); the issuing
      concurrency test fails against a check-then-insert mutation; frontend typecheck, lint, test (33),
      check:api, build green; actionlint 0 errors. Compose smoke job not run locally (needs .env).
- [ ] M1.7 Invariant checker v1 (five checks as SQL) + hard assertion after every integration test
      Note (from M1.3): reuse the hold checks in test `HoldInvariants` (I1 seat in two active
      holds, I2 held seat without its active hold, I3 active hold not owning its seat) instead of
      rewriting them; then make the harness call the M1.7 checker.
      Note (from M1.4): `HoldInvariants` now also has I8 (allowance = seats of active holds, never
      over the limit); take it over as well.
      Note (from M0.2): also check that every event_seat's seat belongs to the event's venue.
      Note (from M1.5, D-017): take over the order rules O1-O5 in test `CheckoutTestSupport` (sold
      seat has exactly one active item in its PAID order; PAID order's seats SOLD to it; no active
      items in CANCELLED orders; money matches; CONVERTED hold <-> PAID order), and report PENDING
      orders older than a checkout takes (the known stuck-checkout cases). `HoldInvariants` is now
      public and its I8 counts ACTIVE + CONVERTED holds.
      Note (from M1.6, D-018): take over the ticket rules T1-T3 in `CheckoutTestSupport` (a live
      ticket's seat is SOLD to its PAID order and its holder is the buyer; an order at TICKETS_ISSUED
      has exactly one live ticket per active item; a PAID order is at TICKETS_ISSUED, i.e. PRD 6.10
      invariant 3). The checker needs a grace period for T3 (checkouts still in step 5).
- [ ] M1.8 Basic seat map page: Canvas2D render of seats, click to hold, checkout, show ticket QR
      Note (from M0.6): SecurityConfig permits GET `/events/*`, which matches one path segment only,
      so `GET /events/{id}/seatmap` needs its own rule (decide whether it is public). Add the venue
      layout (`layout_json`) for the board silhouette / dot-matrix preview with that endpoint.
      Note (from M1.4): add `/holds` to the Vite dev proxy (only /auth, /events, /actuator, /v3 are
      proxied; POST /events/{id}/holds already works through /events). To hold demo seats locally,
      run the backend with `seed,demo-open-sale` (the demo drop is otherwise always in the future and
      holds return 409 SALE_NOT_OPEN). Use `serverTime` from the hold response to correct the timer.
      Note (from M1.5): add `/orders` to the Vite dev proxy too. POST /orders returns the PAID order
      (201) or the existing one (200); 410 means pick seats again; 409 SEAT_TAKEN means retry shortly.
**Exit:** a full purchase works end to end in the browser; concurrency test passes 1000/1000.

## M2 - Contention lab (weeks 4-5)  <- the heart of the portfolio
- [ ] M2.1 Optimistic strategy (version column, bounded retries with jitter)
- [ ] M2.2 Redis-first strategy (Lua: all-or-nothing hold keys with TTL, per-user cap inside Lua,
      async persistence + reconciler)
- [ ] M2.3 Strategy selectable per event (admin endpoint); concurrency test parameterised over all three
- [ ] M2.4 k6 scenarios: seat-collision, mixed-realistic; `scripts/k6.sh` runs k6 in Docker
      Note (from M0.5): log in during k6 `setup()`, not in the measured phase; bcrypt (cost 12) is
      deliberately slow and would dominate the numbers.
      Note (from M1.2 review, D-015): the pessimistic strategy has no `lock_timeout`, so buyers
      queueing on one hot seat each hold a pooled connection (Hikari default 10). Measure pool
      exhaustion in seat-collision; decide on `SET LOCAL lock_timeout` plus a rejection.
- [ ] M2.5 `scripts/bench.sh`: runs each strategy, collects k6 JSON + Postgres CPU, writes
      `benchmarks/results/<date>/` and a markdown results table
- [ ] M2.6 Benchmark report v1 in `benchmarks/README.md` with your own analysis of why the numbers differ
      Note (from M0.4): generate module diagrams now that modules have real edges: run Modulith's
      `Documenter` from a test (`spring-modulith-docs` is already on the test classpath via
      `spring-modulith-starter-test`) and write the output into docs/.
**Exit:** zero double-sells for all three strategies under collision; results table committed.

## M3 - Correct checkout (week 6-7)
- [ ] M3.1 Idempotency keys (store hash + response; same key/different body -> 422) + retry-storm test
      Note (from M1.4, D-016): consider accepting `Idempotency-Key` on POST /events/{id}/holds too.
      M1.4 only recognises a retry by its seats (same seat set as the caller's active hold -> 200).
      Note (from M1.5, D-017): POST /orders has no Idempotency-Key yet (PRD 10 deviation); a retry
      is recognised by its hold (`uq_orders_hold`). Making the header required changes the API the
      M1.8 checkout uses.
- [ ] M3.2 Outbox table + relay to `EventPublisher` (Redis pub/sub impl) + consumer dedupe table
- [ ] M3.3 Mock payment provider as a separate small service: intents, HMAC-signed webhooks, chaos knobs
      Note (from M1.5): replace `payments.provider.FakePaymentProvider` behind `PaymentProvider`.
- [ ] M3.4 Persisted saga orchestrator with compensation; resumes after crash
      Note (from M1.5, D-017): reuse the five steps and the compensation rules of
      `booking.checkout.CheckoutService` (release the hold first; never undo after CONVERTED). Must
      recover PENDING orders left by a crash, by step 4 failing every retry, or by a failed release.
      Re-check the hold in step 1 so an in-flight checkout on an ended hold can't block other buyers
      for the length of an async payment. Add backoff to the step 4 retries.
- [ ] M3.5 Webhook ingestion: signature check, dedupe, out-of-order handling, reconciliation poller
      Note (from M1.5): the poller must resolve payments left CREATED (unknown outcome) by asking the
      provider by payment id.
- [ ] M3.6 Hold extension on payment start; late-success-after-expiry -> automatic refund path
- [ ] M3.7 Checkout UI shows real saga steps over SSE
**Exit:** retry storm, duplicate and out-of-order webhooks: no duplicate orders, invariants pass.
**MVP COMPLETE HERE. Deploy, write the README, record a demo before continuing.**

## M4 - Waiting room (week 8)
- [ ] M4.1 Queue join (Redis sorted set), pre-T-0 lottery, position/ETA over SSE, heartbeats
- [ ] M4.2 Admission scheduler + signed admission tokens; inventory endpoints require them
- [ ] M4.3 Token-bucket rate limits (IP/user/device) in Redis Lua
      Note (from M0.5): rate-limit /auth/refresh and /auth/logout per user/session too. Every
      request for one refresh-token family queues on that family's row lock while holding a DB
      connection, so a flood for one session could tie up the Hikari pool (default 10). Rate
      limiting is the real defence; a `lock_timeout` on the family lock is the fallback if needed.
- [ ] M4.4 Proof-of-work (hashcash) in a Web Worker; measure solve time on a cheap phone
- [ ] M4.5 AIMD adaptive admission driven by p95 latency and error rate
- [ ] M4.6 k6 thundering-herd + bot-swarm scenarios; fairness (Kendall tau) + bot share reported

## M5 - Realtime and frontend (weeks 9-10)
- [ ] M5.1 WebSocket seat channel: snapshot, 100 ms bitset diffs, sequence numbers, resync
      Note (from M0.6): GET /events and /events/{id} deliberately return no availability counts.
      Live demand (seats left, people waiting) for the board and event page comes from realtime.
- [ ] M5.2 Measure hold-to-visible latency against the budget above
- [ ] M5.3 Seat map v2: pan/zoom, semantic zoom, "Taken N ms ago", keyboard navigation
- [ ] M5.4 Hold timer bar + tab-title countdown; waiting room screen (simple version first)
- [ ] M5.5 Control room: queue depth, admission rate, holds/s, p95, errors, invariant lights, pause

## M6 - Resilience and observability (week 11)
- [ ] M6.1 Three API instances behind Nginx; kill one mid-checkout, saga resumes
      Note (from M1.4, D-016): every instance runs the expiry sweeper; duplicates are harmless (the
      hold-row lock serialises them). If the duplicate scans or lock waits show up, claim holds with
      `FOR UPDATE SKIP LOCKED`, one transaction per hold (amends D-015's "not transactional" wording).
- [ ] M6.2 Chaos scripts: Redis restart, payment outage, delayed webhooks; invariants after each
- [ ] M6.3 Prometheus metrics + Grafana dashboards committed as JSON
- [ ] M6.4 Soak test (1 hour)

## M7 - Ship (week 12)
- [ ] M7.1 Deploy backend (one VM, compose) + frontend (Vercel). Before deploying, restrict actuator
      health details (M0.1 set `show-details: always` for local dev). Split DB roles: a migration
      owner role and an app role; `REVOKE UPDATE, DELETE, TRUNCATE ON audit_log` from the app role
      (M0.2 made audit_log append-only with triggers only, which a superuser can bypass)
      Review seeding (from M0.10): compose runs the backend with `seed,demo-reschedule` by default;
      decide whether the deployed instance seeds at all (set `STAMPEDE_COMPOSE_PROFILES` there).
      Note (from M0.5): serve frontend and API same-site (subdomains of one domain, or a Vercel
      rewrite proxying the API), otherwise the SameSite=Strict refresh cookie is never sent.
- [ ] M7.2 README: architecture diagram, decisions and trade-offs, results, how to reproduce
      Note (from M0.9): replace the short root README.md (keep the CI badge) and link to
      docs/DEVELOPING.md for how to run it rather than duplicating the commands.
- [ ] M7.3 Demo video (3 minutes): drop, collision, chaos run, invariants staying green

## Stretch (only when everything above is green)
Kafka/Redpanda publisher, OpenTelemetry + Jaeger, WebGL particle waiting room, ticket reveal and
holographic foil, wallet, transfer with version bump, offline scanner PWA with revocation sync,
presale codes, best-available, resale, venue editor, rotating QR.
- Note (from M0.2) for the offline scanner: make `POST /checkins` idempotent by having the scanner
  generate `checkins.id` (UUID) per scan and resend the same id on sync retries (PK dedupes).
