# Stampede — Product Requirements Document

**Version** 1.0 · **Date** 7 November 2026 · **Author** Manan Vijay · **Status** Draft

---

## 1. Overview

**One-liner.** Stampede is a ticketing platform built for the worst five minutes in ticketing: the moment a sold-out show goes on sale and fifty thousand people press "buy" for two thousand seats.

**Problem.** Ticket drops fail in predictable ways. Servers collapse under the thundering herd, the same seat is sold twice, bots buy everything in seconds, payments time out after the seat is gone, and real fans stare at a spinner with no idea where they stand. Most booking systems are designed for the happy path: one user, one seat, no contention. Stampede is designed from the first line of code around contention, failure and fairness.

**Goals.**

| ID | Goal |
|---|---|
| G1 | **Correctness under contention.** Zero double-sold seats and zero lost paid orders, under every load and failure scenario tested. |
| G2 | **Graceful overload.** When demand exceeds capacity, users queue fairly and visibly instead of seeing errors. |
| G3 | **Measured, not claimed.** Every major architectural decision is backed by a reproducible benchmark committed to the repo. |
| G4 | **Fair access.** Bots and scripted buyers are measurably disadvantaged compared with humans. |
| G5 | **Legible, exceptional frontend.** The interface makes the system's real state visible at every moment and feels unlike any template. |

**Non-goals.** Real payment processing (a mock provider reproduces real-world payment failure modes instead), real money, native mobile apps, multi-region deployment, AI or recommendation features, and social features.

**Success metrics.** These are targets to measure honestly and publish in the README, whatever the real results turn out to be.

| Metric | Target |
|---|---|
| Double-sold seats across all load and chaos runs | 0 |
| Paid orders without issued tickets | 0 |
| Hold request p95 latency at target load (single machine, Docker Compose) | < 50 ms |
| Sustained hold attempts per second (single machine) | Measure and publish; aim for ≥ 2,000 |
| Waiting-room fairness: admission order vs arrival order (Kendall tau) | ≥ 0.95 |
| Share of seats won by bots in the bot-swarm scenario | < 5% |
| Seat map update latency (hold → visible on other clients) | p95 < 300 ms |
| Lighthouse performance score (landing, event page) | ≥ 90 |

---

## 2. Users and core concepts

**Personas.**

| Persona | Needs |
|---|---|
| **Buyer** | A fair shot at seats, honest information about their position, no lost money, and a ticket they trust. |
| **Organizer** | Creates venues and events, sets prices and limits, schedules drops, and watches sales. |
| **Operator** | Runs a drop live: watches system health, tunes admission, pauses or stops the sale in an emergency. |
| **Gate staff** | Scans tickets quickly, even with poor connectivity, and rejects duplicates and fakes. |
| **Adversary** | A bot operator with scripts and many accounts. Not a user; the system is designed against them. |

**Glossary.**

| Term | Meaning |
|---|---|
| Venue | A physical layout of sections, rows and seats with coordinates. |
| Event | A show at a venue, with price tiers and a per-user ticket limit. |
| Drop | The scheduled moment an event goes on sale. |
| Event seat | One seat for one event, carrying its own state and version number. |
| Hold | A temporary, time-limited reservation of one or more seats by one user. |
| Admission token | A short-lived signed token that lets a user out of the waiting room and into seat selection. |
| Order | A purchase attempt covering a hold, moving through a saga of states. |
| Ticket | A signed, offline-verifiable proof of ownership of one event seat. |
| Inventory strategy | The pluggable algorithm that decides how seats are locked (see 6.2). |

**Seat state machine.** Every transition is guarded in code and in the database; any transition not drawn here is rejected.

```
              hold (atomic, all-or-nothing)
 AVAILABLE ───────────────────────────────▶ HELD
     ▲  ▲                                   │  │
     │  └──── expire / release / saga fail ─┘  │ payment confirmed
     │                                         ▼
     └────────────── refund (P2) ─────────── SOLD ──▶ CHECKED_IN
                                               │
                                               └──▶ TRANSFERRED (new holder, new ticket version)
```

---

## 3. Key user journeys

**J1. Drop day (buyer).** Ten minutes before the drop, the buyer opens the event page and sees a live countdown and how many people are already waiting. At T-0 they join the waiting room; their browser silently solves a proof-of-work puzzle while they watch their live position and ETA. When admitted, they receive an admission token valid for ten minutes and enter a live seat map where seats light up and dim as others grab them. They select up to the per-user limit; the seats are held for five minutes and a draining timer appears at the top of the screen. At checkout, the order is submitted with an idempotency key, so refreshing or double-clicking is safe. They watch the order saga progress step by step until payment is confirmed. The ticket "prints" onto the screen and lands in their wallet.

**J2. Payment goes wrong.** When payment starts, the hold is extended once by 60 seconds. If the payment fails or times out, the saga compensates: the order is cancelled, the seats are released and broadcast as available, and the buyer is told plainly what happened. If a payment succeeds *after* the hold has already expired and the seats were resold, the system detects the late success and automatically refunds it.

**J3. Organizer sets up an event.** The organizer picks or builds a venue, creates an event, assigns price tiers to sections, sets a per-user limit, and schedules the drop, optionally with a presale window protected by access codes.

**J4. Operator runs the drop.** In the control room, the operator watches queue depth, admission rate, hold conflicts, latency and error rate in real time. The adaptive admission controller adjusts throughput automatically; the operator can override it, pause the sale, extend all holds during an incident, or hit the kill switch.

**J5. Gate check-in.** Gate staff scan the QR code. The scanner verifies the ticket's signature offline using the platform's public key, records the check-in, and syncs when online. A second scan of the same ticket shows when and where it was first scanned.

**J6. Transfer.** A buyer transfers a ticket to a friend. The ticket version increments, the old QR code stops verifying, and the friend's wallet receives the new one.

---

## 4. Functional requirements

Priority: **P0** = MVP must-have, **P1** = v1, **P2** = stretch.

**Buyer.**

| ID | Requirement | Priority |
|---|---|---|
| B1 | Browse upcoming drops with live countdowns and status. | P0 |
| B2 | Event page with venue preview, price tiers, rules and live demand indicator. | P0 |
| B3 | Join the waiting room at drop time; see live position and an honest ETA. | P0 |
| B4 | Select seats on a live-updating seat map, up to the per-user limit. | P0 |
| B5 | Seats held for 5 minutes with a visible countdown; manual release. | P0 |
| B6 | Checkout with idempotent submission; refreshing or retrying never creates a second order. | P0 |
| B7 | Ticket wallet with signed QR code. | P0 |
| B8 | "Best available": auto-select N adjacent seats in a chosen tier. | P1 |
| B9 | Filters for accessible and aisle seats. | P1 |
| B10 | Notifications for admission, hold expiring and order confirmed (in-app and email). | P1 |
| B11 | Transfer a ticket to another user. | P1 |
| B12 | Resale at a price capped at face value through the platform. | P2 |

**Organizer.**

| ID | Requirement | Priority |
|---|---|---|
| O1 | Create events with price tiers per section and a per-user ticket limit. | P0 |
| O2 | Seed venues from JSON layout files. | P0 |
| O3 | Schedule a drop; optional presale window with access codes. | P1 |
| O4 | Sales dashboard: sold, held, revenue and sell-through curve over time. | P1 |
| O5 | Visual venue editor: sections, curved rows, aisles, generated from parameters. | P2 |

**Operator.**

| ID | Requirement | Priority |
|---|---|---|
| OP1 | Live control room: queue depth, admission rate, active holds, conversion, error rate, p95 latency. | P0 |
| OP2 | Switch the inventory strategy per event (non-production, for benchmarking). | P0 |
| OP3 | Manual and adaptive admission-rate control. | P1 |
| OP4 | Pause sale, extend all holds, kill switch. | P1 |
| OP5 | Live invariant monitor showing the status of every correctness check. | P1 |
| OP6 | Chaos panel: inject payment failures and latency, kill a worker. | P2 |

**Gate.**

| ID | Requirement | Priority |
|---|---|---|
| GT1 | Mobile web scanner that verifies tickets offline via public-key signature. | P1 |
| GT2 | Check-in sync with duplicate-scan detection showing the original scan's time and gate. | P1 |

---

## 5. Backend architecture

**Approach: a modular monolith, run as several instances.** The backend is one Spring Boot codebase with strictly enforced module boundaries, deployed as three identical stateless API instances behind Nginx, plus separate worker processes. Microservices would add deployment overhead without teaching anything more about the core problem. Strict module boundaries keep the design disciplined and allow any module to be split out later. This decision and its trade-offs should be written up in the README.

**Modules.**

| Module | Responsibility |
|---|---|
| Edge (Nginx) | TLS, load balancing, first-layer rate limiting, WebSocket upgrade. |
| Identity | Registration, login, short-lived JWT access tokens with rotating refresh tokens, device fingerprints. |
| Waiting room | Queue join, proof-of-work verification, admission scheduling, admission tokens. |
| Inventory | Seat state, pluggable locking strategies, holds and expiry. |
| Booking | Orders, idempotency, the checkout saga orchestrator. |
| Payments | Provider adapter, signed webhook ingestion, reconciliation polling. |
| Ticketing | Ticket issuing and signing, transfer, check-in. |
| Realtime | WebSocket and SSE fan-out of seat, queue and saga updates. |
| Notifications | In-app and email messages. |
| Control | Admin APIs, metrics aggregation, chaos hooks, invariant checker. |

**Data stores.** PostgreSQL is the single source of truth for seats, orders, tickets and the outbox. Redis is the fast path, holding the queue, seat holds, rate-limit buckets and pub/sub for fan-out; it is fast but never trusted alone, and a reconciler keeps it consistent with Postgres. Kafka (Redpanda locally) carries domain events such as `SeatHeld`, `SeatReleased`, `OrderPlaced`, `PaymentSucceeded` and `TicketIssued` to the realtime, notification, analytics and reconciliation consumers.

**Request flow.**

```
Browser ──▶ Nginx ──▶ API instance ×3 (stateless) ──▶ Redis  (queue, holds, rate limits, pub/sub)
                         │                       └──▶ PostgreSQL (source of truth + outbox)
                         │
                         └── outbox relay ──▶ Kafka ──▶ realtime fan-out ──▶ WebSocket/SSE ──▶ Browser
                                                   ├──▶ notifications
                                                   ├──▶ analytics / control room
                                                   └──▶ reconciler

Mock payment provider ── signed webhooks ──▶ API ──▶ saga orchestrator
```

---

## 6. Backend deep features

### 6.1 Virtual waiting room

At drop time, users join a queue stored in a Redis sorted set, scored by arrival time plus a server-side sequence number, so ordering is fair and deterministic.

Before joining, the browser must solve a **proof-of-work puzzle** (hashcash-style, adjustable difficulty). It costs a real browser one to two seconds and costs a bot farm real CPU at scale.

A scheduler admits K users per second. Each admitted user receives a **signed admission token** (a short-lived JWT bound to the user, device and event), and every inventory call requires a valid one.

Admission is **adaptive**, using an AIMD controller borrowed from TCP congestion control. The rate increases additively while p95 latency and error rate stay healthy, and halves immediately when either breaches its threshold. The resulting sawtooth is plotted live in the control room.

Positions and ETAs are pushed every two seconds, with the ETA computed from the current admission rate. Clients send heartbeats, and users who leave are removed, so the ETA stays honest.

Fairness is verified by comparing admission order with arrival order (Kendall tau) in load tests.

### 6.2 Pluggable inventory strategies

This is the benchmark core of the project. A single `InventoryStrategy` interface has three implementations, selectable per event:

| Strategy | Mechanism |
|---|---|
| **A. Pessimistic** | `SELECT … FOR UPDATE` on the requested event-seat rows inside a transaction. To avoid deadlocks, seats are always locked in sorted ID order. |
| **B. Optimistic** | A version column per event seat; `UPDATE … WHERE status = 'AVAILABLE' AND version = ?` with bounded retries and jitter. |
| **C. Redis-first** | A Lua script that atomically checks and sets hold keys for all requested seats, with a TTL. Persistence to Postgres is asynchronous via events, guarded by the reconciler. |

Every strategy must make multi-seat holds **all-or-nothing**: a request for four seats gets all four or none.

Regardless of strategy, a **unique partial index** in Postgres guarantees that a seat can never appear in two active orders. This is the last line of defence even if application code is wrong.

A benchmark harness runs identical k6 scenarios against each strategy and publishes throughput, p50/p95/p99 latency, conflict rate and database CPU in a results table.

### 6.3 Holds and expiry

Holds last five minutes, with one 60-second extension allowed when payment starts. Expiry uses two mechanisms. Redis keyspace notifications give fast release, but their delivery is not guaranteed, so a periodic sweeper over Postgres acts as a safety net. Every release is broadcast to seat-map clients immediately.

### 6.4 Checkout saga and idempotency

`POST /orders` and payment calls require an `Idempotency-Key` header. The server stores the key with a hash of the request and the response. The same key with the same body returns the stored response; the same key with a different body returns `422`.

Checkout runs as a persisted, orchestrated saga:

```
HoldVerified ─▶ OrderCreated(PENDING) ─▶ PaymentInitiated ─▶ PaymentSucceeded ─▶ SeatsSold ─▶ TicketsIssued ─▶ Notified
                                                   │
                                                   └─▶ PaymentFailed / Timeout ─▶ OrderCancelled ─▶ SeatsReleased
```

Saga state lives in Postgres, so if an API instance crashes mid-checkout, another instance resumes the saga from its last completed step. A payment that succeeds after its hold expired triggers an automatic refund path.

### 6.5 Transactional outbox

Every state change and its corresponding event row are written in the same database transaction. A relay publishes outbox rows to Kafka. Consumers record processed event IDs and skip duplicates, giving at-least-once delivery with effectively-once processing. Change-data capture in place of polling is a stretch goal.

### 6.6 Mock payment provider with chaos

A small separate service mimics real payment-gateway semantics: create a payment intent, confirm it from the client, and deliver HMAC-signed webhooks. Its chaos controls include:

- a configurable latency distribution
- a failure percentage
- duplicate webhooks
- out-of-order webhooks
- webhooks delayed by minutes
- full provider outage

The backend must survive all of them through signature verification, webhook deduplication, state-machine guards that reject invalid transitions, and a reconciliation poller that catches missing webhooks.

### 6.7 Bot defence and abuse prevention

Defence is layered:

- proof-of-work at queue entry
- token-bucket rate limits per IP, user and device, enforced atomically in Redis
- admission tokens bound to a device fingerprint
- a per-user ticket cap enforced transactionally across concurrent requests, not just in the UI
- anomaly flags for inhumanly fast selection patterns, which trigger a step-up challenge

A dedicated bot-swarm load scenario measures how many seats bots manage to win.

### 6.8 Realtime seat-map fan-out

Each API instance subscribes to seat events. Clients subscribe per event and section over WebSocket. The server coalesces changes into **100 ms diff batches**, sending compact bitset deltas rather than one message per seat. Every message carries a sequence number, so a client that detects a gap requests a fresh snapshot. Slow clients receive periodic snapshots instead of diffs, so one bad connection never backs up the server. Messages per second and hold-to-visible latency are measured and published.

### 6.9 Signed, offline-verifiable tickets

A ticket payload (ticket ID, event, seat, holder, issue time, version) is signed with **Ed25519**. The QR code encodes the payload and signature, so the scanner verifies authenticity offline using only the public key.

Transfer increments the version, invalidating the old QR. Check-in is idempotent and records gate and time. A rotating QR, refreshed every 30 seconds from a TOTP-style secret to defeat screenshots, is a stretch goal.

### 6.10 Correctness invariants and reconciliation

An invariant checker runs continuously during operation and as a hard assertion after every test. It checks five rules:

1. No seat belongs to more than one active order.
2. Sold seats never exceed capacity.
3. Every paid order has issued tickets.
4. Every sold seat has a paid order.
5. Redis hold state is consistent with Postgres.

Violations light up red in the control room. A reconciler repairs any drift between Redis and Postgres.

### 6.11 Observability

OpenTelemetry tracing runs end to end, from browser request through Nginx, API, Redis, Postgres and Kafka to the consumers, viewable in Jaeger. Prometheus collects metrics, and Grafana dashboards are committed to the repo as JSON. Logs are structured JSON with trace IDs.

Custom metrics include queue depth, admission rate, hold success and conflict rates, saga outcomes and webhook anomalies.

---

## 7. Frontend design language

**Direction: "departure board meets Swiss poster."** The interface is precise, typographic and monochrome, with exactly one signal colour reserved for liveness, urgency and "yours." Real data is the decoration. Every animation communicates a change in state; nothing moves just to look busy.

**Anti-slop rules.**

| Never | Instead |
|---|---|
| Purple or blue gradients, gradient text | Flat ink and paper with a single signal colour |
| Glassmorphism blur cards | Hairline rules and a strict visible grid |
| Emoji, sparkles, "✨ magical" copy | Terse, confident, factual copy ("Taken 40 ms ago.") |
| Stock 3D blobs and illustrations | Real data rendered beautifully: seats, queues, timings |
| Default component-library card grids with soft shadows | Typographic tables, dotted leaders, generous whitespace |
| Spinners | Honest progress: positions, saga steps, hash streams |

**Colour.**

| Token | Value | Use |
|---|---|---|
| Ink | `#0B0B0C` | Background in dark mode, text in light mode |
| Paper | `#F2F0EB` | Text in dark mode, background in light mode |
| Graphite | `#8A8A8E` | Secondary text, inactive seats |
| Hairline | Paper at 12% opacity | Rules, grid lines, borders |
| Signal | `#FF3B1F` | Live states, urgency, the user's own seats and queue dot only |
| Confirm | `#2F6B4F` | Used once: confirmed order and valid scan |

Dark mode is the default, with a full light mode by inverting ink and paper.

**Typography.** Display and UI text use a tight grotesk such as General Sans or Geist. Every number, timer, seat code and price uses a monospace face (Geist Mono or JetBrains Mono) with tabular figures, so digits never shift as they change. Countdowns run huge, up to 160 px via `clamp()`. Micro-labels are uppercase with wide letter-spacing.

**Motion principles.**

- **Duration:** 120–320 ms for UI transitions.
- **Physics:** spring physics for anything the user drags or zooms.
- **Easing:** `cubic-bezier(0.2, 0.8, 0.2, 1)` for everything else.
- **Rendering:** anything with more than about 500 moving elements is drawn on canvas or WebGL to hold 60 fps.
- **Reduced motion:** every effect respects `prefers-reduced-motion`, falling back to simple crossfades.

---

## 8. Frontend screens and signature effects

**8.1 Landing: the departures board.** Upcoming drops are listed like an airport split-flap board. Characters flip into place on load and whenever a status changes (`ON SALE IN 02:14:09` → `LIVE` → `SOLD OUT`). A giant mono countdown shows the next drop. Hovering a row expands it and draws the venue's silhouette as a single animated SVG line.

**8.2 Event page.** A shared-element transition (View Transitions API) carries the board row into the event page header. The venue appears as a dot-matrix preview. Price tiers are set as a typographic table, and a live sparkline shows how many people are waiting.

**8.3 Waiting room (the centerpiece).** A full-screen WebGL particle field shows every person in the queue as a point, up to 50,000. The user's point glows in signal colour. As people ahead are admitted, their points stream off the right edge and the field drifts forward. The position is a huge odometer-style rolling number, and the ETA tweens smoothly instead of jumping. The proof-of-work runs visibly as a thin cascading line of hex hashes labelled "verifying you're human," honest about what is actually happening.

**8.4 Seat map.** A canvas or WebGL renderer handles 20,000+ seats with inertial pan, zoom and pinch.

- **Semantic zoom:** sections appear as blocks when zoomed out and resolve into individual seats when zoomed in.
- **Live seats:** when someone else holds a seat, it pulses once, a ripple spreads, and it dims.
- **Heat mode:** a toggle overlays live demand as a thermal map.
- **Magnifier lens:** follows the cursor over dense sections.
- **Crosshair readout:** a custom crosshair cursor shows `SEC 104 · ROW K · SEAT 12 · ₹2,400`.
- **Losing the race:** if the user loses a seat to someone else, it shakes once and the message reads "Taken 40 ms ago," using the real timing from the server.
- **Keyboard:** arrow keys move between seats.

**8.5 Hold timer.** A 2 px bar across the top of the viewport drains over five minutes. Under 60 seconds it turns signal colour and the digits pulse each second; under 10 seconds the viewport edge flashes once per second. The browser tab title becomes the countdown (`04:12 — Stampede`) so it works in a background tab.

**8.6 Checkout.** A single-column, receipt-style summary in mono with dotted leaders. While payment is pending, the actual saga steps stream in over SSE as a vertical timeline: seats locked, payment authorised, tickets issuing. It shows backend truth instead of a spinner.

**8.7 Ticket reveal.** The ticket prints out of a slot with a spring motion, the perforated edge is drawn, and the QR code resolves out of noise. The ticket then becomes a 3D card with a holographic foil shader that responds to the mouse or device gyroscope. The foil pattern is generated from the ticket ID, so every ticket is unique.

**8.8 Wallet.** Tickets stack like physical cards and fan out on drag. A card flips to show details, and a progress ring indicates QR rotation.

**8.9 Control room.** A dense, dark, mono operator view:

- live charts of queue depth, admission rate (showing the AIMD sawtooth), holds per second, p95 latency and errors
- a seat heat overview
- invariant status lights
- sliders for admission rate
- a guarded kill switch that requires a two-second hold with a filling ring

**8.10 Scanner.** A full-screen camera view. A scan triggers a full-screen flash:

- **Valid:** paper colour with seat info.
- **Duplicate:** signal colour showing `SCANNED 14:02 · GATE 3`.
- **Invalid signature:** a distinct rejection state.

**Global details.** Loading skeletons are replaced by typographic placeholders with a blinking cursor, and toasts appear as terminal-style lines in a corner. Optional subtle sound on seat selection is off by default.

**Performance budgets.** Initial route JavaScript under 200 KB gzipped, with WebGL code split out. LCP under 2.0 s and INP under 200 ms. The seat map holds 60 fps with 20,000 seats on a mid-range laptop.

---

## 9. Data model

All money is stored as integer paise. All timestamps are UTC.

| Table | Key columns | Notes |
|---|---|---|
| `users` | id, email, password_hash, created_at | |
| `devices` | id, user_id, fingerprint_hash, first_seen | Binds admission tokens and rate limits |
| `venues` | id, name, layout_json | |
| `sections` | id, venue_id, code, default_tier | |
| `seats` | id, section_id, row_label, number, x, y, attrs | attrs: aisle, accessible |
| `events` | id, venue_id, title, starts_at, drop_at, status, per_user_limit, strategy | |
| `price_tiers` | id, event_id, name, price_paise | |
| `event_seats` | event_id, seat_id, tier_id, status, version, hold_id, order_id | PK (event_id, seat_id); index on (event_id, status) |
| `holds` | id, event_id, user_id, seat_ids, expires_at, extended, status | |
| `orders` | id, user_id, event_id, status, total_paise, saga_state, created_at | |
| `order_items` | order_id, event_id, seat_id, price_paise, active | **Unique partial index on (event_id, seat_id) WHERE active** |
| `payments` | id, order_id, provider_ref, status, amount_paise, raw_events | raw_events as jsonb for audit |
| `idempotency_keys` | key, user_id, request_hash, response, status, expires_at | |
| `outbox` | id, aggregate_type, aggregate_id, event_type, payload, created_at, published_at | |
| `processed_events` | consumer, event_id | PK (consumer, event_id) for consumer dedupe |
| `tickets` | id, order_id, event_id, seat_id, holder_user_id, version, signature, status | |
| `checkins` | id, ticket_id, gate, scanned_at, result | |
| `audit_log` | id, actor, action, entity, before, after, at | Append-only |

---

## 10. API and realtime contract

The OpenAPI spec is the source of truth, and the TypeScript client is generated from it (the same pattern as InstaVEG).

| Method & path | Purpose | Notes |
|---|---|---|
| `POST /auth/register`, `/auth/login`, `/auth/refresh` | Identity | Refresh-token rotation |
| `GET /events`, `GET /events/{id}` | Browse | Public. List: upcoming non-draft events as `{items, serverTime}`, at most 100, no pagination yet. Detail adds tiers with prices. No availability counts (live demand comes from realtime) |
| `GET /events/{id}/layout` | Static seat layout | Public. Venue outline, stage, sections, every seat with position and tier, sorted by seat id; a seat's position is its bit index in the seat map (D-020) |
| `GET /events/{id}/seatmap` | Seat snapshot | Public. Availability bitset (1 bit per seat, layout order) + `asOf`; the sequence number arrives with realtime in M5.1 (D-020) |
| `POST /events/{id}/queue` | Join waiting room | Body includes PoW solution |
| `GET /queue/{ticket}` | Position stream | SSE |
| `POST /events/{id}/holds` | Hold seats | Requires admission token; all-or-nothing |
| `DELETE /holds/{id}` | Release hold | |
| `POST /orders` | Create order from hold | Requires `Idempotency-Key` (from M3.1; until then a retry is recognised by its hold, which has at most one order, D-017) |
| `GET /orders/{id}` | Order | Owner only; anyone else gets 404 like an unknown id (M1.5) |
| `GET /orders/{id}/saga` | Saga progress | SSE |
| `POST /payments/webhook` | Provider callbacks | HMAC-verified, deduplicated |
| `GET /me/tickets`, `POST /tickets/{id}/transfer` | Wallet, transfer | |
| `POST /checkins` | Gate scan sync | Idempotent |
| `POST /admin/events/{id}/admission-rate`, `/pause`, `/strategy`, `/chaos` | Operator controls | Admin role only |
| `WS /ws/events/{id}/seats?sections=…` | Live seat map | `snapshot`, `diff`, `resync-required` messages |

**Errors** follow RFC 7807 (`application/problem+json`) with stable codes: `SEAT_TAKEN` (including `takenAgoMs`), `SEAT_CONTENDED`, `HOLD_EXPIRED`, `LIMIT_EXCEEDED`, `NOT_ADMITTED`, `RATE_LIMITED`, `IDEMPOTENCY_CONFLICT`.

Auth and request errors (added in M0.5, D-008). Every problem body carries the code in a `code` property:

| Code | Status | When |
|---|---|---|
| `VALIDATION_FAILED` | 400 | Request is invalid: body invalid or not JSON (field errors listed in `errors`), or a malformed path/query parameter such as a non-UUID id |
| `UNAUTHENTICATED` | 401 | Missing, invalid, tampered or expired access token |
| `INVALID_CREDENTIALS` | 401 | Login failed; the same response for an unknown email and a wrong password |
| `INVALID_REFRESH_TOKEN` | 401 | Refresh token missing, unknown, expired, already used (reuse) or revoked; the reason is not exposed |
| `FORBIDDEN` | 403 | Authenticated but not allowed (e.g. a non-admin on `/admin/**`), or the `X-Requested-With` header is missing on refresh/logout |
| `EMAIL_TAKEN` | 409 | Registration with an email that already exists (case-insensitive) |

Browse and general request errors (added in M0.6, D-010):

| Code | Status | When |
|---|---|---|
| `EVENT_NOT_FOUND` | 404 | `GET /events/{id}` for an id that doesn't exist or is a DRAFT (drafts are indistinguishable from unknown ids) |
| `NOT_FOUND` | 404 | No route at this path (only for requests that pass the security rules; see below) |
| `METHOD_NOT_ALLOWED` | 405 | The path exists but not for this method; the `Allow` header lists the methods |
| `UNSUPPORTED_MEDIA_TYPE` | 415 | The request body's content type isn't accepted (e.g. `text/plain` instead of JSON) |
| `REQUEST_FAILED` | other 4xx | Any other client error the framework rejects; the response keeps the framework's status |
| `INTERNAL_ERROR` | 500 | An unexpected server error. The body has a fixed message and an `errorId` that matches the server log; it never contains exception messages, SQL or stack traces |

Checkout errors (added in M1.5, D-017):

| Code | Status | When |
|---|---|---|
| `HOLD_NOT_FOUND` | 404 | `POST /orders` for a hold id that is unknown or someone else's (indistinguishable) |
| `HOLD_EXPIRED` | 410 | The hold expired or was released before it became an order; nothing is sold and any payment taken is refunded |
| `PAYMENT_FAILED` | 402 | The payment provider declined; the order is cancelled and the hold's seats are released |
| `SEAT_TAKEN` | 409 | A seat of the hold is still part of another order that hasn't finished (in flight, or crashed before it could clean up); retry shortly; `seatIds`, no `takenAgoMs` |
| `ORDER_NOT_FOUND` | 404 | `GET /orders/{id}` for an id that is unknown or someone else's order (indistinguishable) |

Hold contention (added in M2.1, D-021):

| Code | Status | When |
|---|---|---|
| `SEAT_CONTENDED` | 409 | `POST /events/{id}/holds` on an optimistic-strategy event: every try lost a race on a seat that was still free when last read, so nothing was held; `seatIds` (the seat the last try lost on), header `Retry-After: 1`. A seat that is actually HELD or SOLD is `SEAT_TAKEN` instead |

**Security runs before routing (401 before 404/405).** The security filter checks a request before
Spring looks for a matching route, and every route that isn't explicitly public requires a token. So
without a valid token, an unknown path or a wrong method on a public path (e.g. `DELETE /events/{id}`)
returns `401 UNAUTHENTICATED`, not 404 or 405. With a valid token, the same requests return
`NOT_FOUND` or `METHOD_NOT_ALLOWED`. This is deliberate deny-by-default: an anonymous caller can't
probe which routes exist.

**The spec.** The OpenAPI spec is generated from the code and committed at `docs/openapi.json`; a test
fails if the two differ (D-010). Every operation documents its problem+json responses under the status
of each code it can return, plus `500 INTERNAL_ERROR`.

---

## 11. Non-functional requirements

| Area | Requirement |
|---|---|
| Correctness | The five invariants in 6.10 hold after every test run, every load run and every chaos run. |
| Performance | Targets in section 1; all results reproducible from scripts in the repo. |
| Availability | Losing one API instance mid-drop loses no orders; in-flight sagas resume elsewhere. A Redis restart is followed by automatic reconciliation. |
| Security | bcrypt or Argon2 password hashing, short-lived JWTs with rotating refresh tokens, HMAC-verified webhooks, Ed25519 tickets, ownership checks on every resource (no IDOR), OWASP Top 10 review, secrets only via environment. |
| Privacy | Minimal personal data; device fingerprints stored only as hashes. |
| Accessibility | WCAG 2.1 AA, full keyboard seat selection, screen-reader labels on seats, reduced-motion support. |
| Operability | One-command startup (`docker compose up`), health checks, dashboards and runbooks in the repo. |

---

## 12. Testing, load and chaos strategy

**Automated tests.**

| Test | What it covers |
|---|---|
| Unit tests | Domain logic and the seat state machine. |
| Property-based tests (jqwik) | Random sequences of seat operations never reach an illegal state. |
| Integration tests (Testcontainers) | Real Postgres, Redis and Kafka. |
| Concurrency tests | N threads race for the same seats and exactly one wins, repeated 1,000 times per strategy. |
| Contract tests | The API stays consistent with the OpenAPI spec. |
| End-to-end tests (Playwright) | The full drop journey in a browser. |

**Load scenarios (k6).**

| Scenario | What it proves |
|---|---|
| Thundering herd | Thousands of virtual users join the queue at T-0; the waiting room absorbs it. |
| Seat collision | Every user targets the same 100 seats; zero double-sells, conflict rate measured per strategy. |
| Retry storm | Clients retry aggressively with and without idempotency keys; no duplicate orders. |
| Slow payments | Webhooks delayed and duplicated; sagas and holds behave correctly. |
| Bot swarm | Scripted clients without PoW or with many accounts; bot seat share measured. |
| Soak | One hour of steady traffic; no leaks, no drift, no invariant violations. |

**Chaos scenarios.** Kill an API instance mid-checkout, restart Redis during a drop, pause Kafka, take the payment provider down, and inject out-of-order webhooks. The invariant checker must pass after every run.

Results, raw data and charts are committed under `/benchmarks` and summarised in the README.

---

## 13. Tech stack

| Layer | Choice | Why |
|---|---|---|
| Backend language | Java 21 + Spring Boot 3 | Virtual threads for massive concurrency; a strong OOP language on your profile |
| Data access | jOOQ or Spring JDBC + Flyway | Explicit SQL and explicit locking, nothing hidden by an ORM |
| Database | PostgreSQL 16 | Source of truth; row locks, partial unique indexes |
| Cache / queue | Redis 7 | Sorted-set queue, Lua atomic holds, rate limits, pub/sub |
| Events | Redpanda (Kafka-compatible) | Kafka semantics with a lighter local footprint |
| Edge | Nginx | Load balancing across three API instances, WebSockets |
| Frontend | React 19, TypeScript, Vite, TanStack Query, Zustand | |
| Motion & graphics | Motion (Framer Motion), OGL or regl for particles, PixiJS or Canvas2D for the seat map, React Three Fiber for the ticket | |
| Observability | OpenTelemetry, Prometheus, Grafana, Jaeger | |
| Testing | JUnit 5, Testcontainers, jqwik, Playwright, k6 | |
| Email (local) | Mailpit | |
| CI | GitHub Actions: build, tests, a smoke load test | |
| Deploy | Docker Compose on one cloud VM for the backend; frontend on Vercel | |

---

## 14. Milestones and scope tiers

Estimated at about ten weeks of steady part-time work.

| Milestone | Weeks | Scope | Exit criteria |
|---|---|---|---|
| M0 Skeleton | 1 | Repo, Compose, CI, schema, auth, seeded venue, static event page | `docker compose up` runs everything; CI green |
| M1 Core booking | 2–3 | Basic canvas seat map, holds (pessimistic strategy), orders, mock payment happy path, tickets, invariant checker v1 | A complete purchase works end to end |
| M2 Contention | 4–5 | Optimistic and Redis strategies, k6 harness, benchmark report v1, idempotency, saga with compensation, outbox to Kafka | Zero double-sells under the collision scenario for all three strategies |
| M3 Fairness | 6 | Waiting room, admission tokens, PoW, rate limits, adaptive admission | Thundering herd and bot swarm scenarios pass targets |
| M4 Realtime + frontend | 7–8 | WebSocket diffs, particle waiting room, seat map effects, hold timer, ticket reveal, control room | Hold-to-visible p95 under 300 ms; 60 fps seat map |
| M5 Resilience | 9 | Chaos scenarios, payment chaos, tracing, dashboards | All chaos runs pass the invariant checker |
| M6 Ship | 10 | Deployment, README, architecture diagram, benchmark write-up, demo video | Anyone can run it and reproduce the numbers |

**Scope tiers.** MVP covers every P0 requirement and milestones M0–M2. v1 adds the P1 requirements and M3–M5. Stretch covers resale, the visual venue editor, rotating QR codes, change-data capture and sound design.

**Scope rule:** never start a stretch item while any P0 test is failing, and never start frontend effects before M3 is complete.

---

## 15. Risks, definition of done, open questions

**Risks.**

| Risk | Mitigation |
|---|---|
| Scope explosion | Strict tiers and the scope rule above; cut stretch items before cutting tests or docs. |
| A laptop can't generate enough load | Focus on relative comparisons between strategies; run k6 from a second machine or a cloud runner for headline numbers. |
| WebGL performance on weak devices | Canvas2D fallbacks and reduced-effect mode, detected automatically. |
| Kafka operational complexity | Single-node Redpanda locally; the outbox keeps the design correct even if the broker is briefly down. |
| Frontend polish consuming the schedule | Effects are only allowed after M3 and are time-boxed. |

**Definition of done.**

| Criterion | Verified by |
|---|---|
| Runs with one command | `docker compose up` on a clean machine |
| Zero double-sells and zero lost orders | Invariant checker passing after every load and chaos run, with logs committed |
| Strategies benchmarked | Results table and charts in `/benchmarks` and the README |
| Deployed | Live URL plus demo video |
| Tested | CI green, including concurrency and integration tests |
| Documented | README with architecture diagram, design decisions and trade-offs, setup, and results |
| Own work | Steady commit history; you can explain every component in an interview |

**Open questions.**

1. Seat map renderer: PixiJS or hand-written Canvas2D? Spike both in week 1 with 20,000 seats and pick the faster.
2. Polling outbox relay or change-data capture? Start with polling; CDC is a stretch goal.
3. Cloud target for the backend VM, to be chosen by cost.
4. Should presale access codes be single-use or multi-use? Decide before M3.
