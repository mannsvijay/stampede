// seat-collision (M2.4a, D-024; PRD 12 "every user targets the same 100 seats"): the drop.
//
// VUS buyers (default 500), each a different user, all want seats from the same block of HOT_SEATS seats
// (default 100) of the benchmark event. They wait for the drop by the server's clock (polling the event
// like a refreshing browser for the last seconds), then each one loops up to MAX_TRIES times: read the
// seat map, pick 1-4 free seats of the block at random, POST a hold; a winner buys at once (POST /orders),
// a loser (SEAT_TAKEN / SEAT_CONTENDED) waits 50-250 ms and tries again; nobody tries once the block is
// gone. Before that, a warm-up phase (WARMUP_SECONDS) holds and releases seats on the warm-up event, so
// the JVM and pools are warm; it is never measured.
//
// Gated (correctness, D5-A): no unexpected answer, at most HOT_SEATS seats held and sold, a strict and
// conclusive invariant verdict; k6.sh adds "the database sold exactly what k6 counted".
// Reported, not gated: hold latency p50/p95/p99 by outcome, answers per code, holds/s, conflict rate,
// time until the block was gone.
//   ./scripts/k6.sh seat-collision --strategy OPTIMISTIC --vus 500
import { sleep } from 'k6';
import exec from 'k6/execution';
import { Counter, Trend } from 'k6/metrics';
import { config } from './lib/config.js';
import { get, post, del, body, code, login } from './lib/api.js';
import { prepareUsers } from './lib/users.js';
import { loadLayout, hotBlock, fetchAvailability, isAvailable, pickRandom } from './lib/seatmap.js';
import {
  CORRECTNESS_THRESHOLDS, initThresholdMetrics, msUntilDrop, switchBenchEvent, unexpected, verdict,
} from './lib/run.js';
import { writeSummary, latency, num } from './lib/summary.js';

const HOLD_CODES = ['HELD', 'SEAT_TAKEN', 'SEAT_CONTENDED', 'SALE_NOT_OPEN', 'LIMIT_EXCEEDED', 'OTHER'];

const holdDuration = new Trend('hold_duration', true);
const holdDurationWon = new Trend('hold_duration_won', true);
const holdDurationLost = new Trend('hold_duration_lost', true);
const holdAnswers = Object.fromEntries(HOLD_CODES.map((c) => [c, new Counter(`hold_${c}`)]));
const orderDuration = new Trend('order_duration', true);
const seatsHeld = new Counter('seats_held');
const seatsSold = new Counter('seats_sold');
const buyersGaveUp = new Counter('buyers_gave_up');
const buyersFoundBlockGone = new Counter('buyers_found_block_gone');
// Milliseconds after the drop (server clock) of every hold attempt, and of every won hold: min/max give
// the race's window, so holds/s and "block gone after" don't include setup or warm-up.
const attemptAfterDrop = new Trend('hold_attempt_ms_after_drop');
const wonAfterDrop = new Trend('hold_won_ms_after_drop');

export const options = {
  scenarios: {
    warmup: {
      executor: 'constant-arrival-rate',
      exec: 'warmup',
      rate: config.warmupRate,
      timeUnit: '1s',
      duration: `${config.warmupSeconds}s`,
      preAllocatedVUs: config.warmupUsers,
      tags: { phase: 'warmup' },
    },
    collision: {
      executor: 'per-vu-iterations',
      exec: 'collide',
      vus: config.vus,
      iterations: 1,
      startTime: `${config.warmupSeconds}s`,
      // Generous: a buyer must never be cut off mid-request (the verdict is strict).
      maxDuration: '10m',
      tags: { phase: 'measure' },
    },
  },
  thresholds: {
    ...CORRECTNESS_THRESHOLDS,
    seats_held: [`count<=${config.hotSeats}`],
    seats_sold: [`count<=${config.hotSeats}`],
  },
  setupTimeout: '10m',
  teardownTimeout: '5m',
  summaryTrendStats: ['avg', 'min', 'med', 'p(95)', 'p(99)', 'max', 'count'],
};

export function setup() {
  initThresholdMetrics();
  seatsHeld.add(0);
  seatsSold.add(0);
  switchBenchEvent(login(config.adminEmail, config.adminPassword));
  const tokens = prepareUsers(config.vus + config.warmupUsers);

  const bench = loadLayout(config.benchEventId, 'setup-layout');
  const hot = hotBlock(bench.seats, config.hotSeats).map((s) => ({ id: s.id, bit: s.bit }));
  // Setup data is copied into every VU, so the warm-up gets a sample of seats, not all ~2,000.
  const warm = pickRandom(loadLayout(config.warmupEventId, 'setup-layout').seats, 200)
    .map((s) => ({ id: s.id, bit: s.bit }));

  // The race must start after setup and the warm-up, at the drop. If logins took too long, the buyers
  // would arrive after the drop one by one instead of together: stop and ask for a longer lead.
  const untilDrop = msUntilDrop();
  const needed = config.warmupSeconds * 1000 + 5000;
  if (untilDrop < needed) {
    throw new Error(`the drop is ${(untilDrop / 1000).toFixed(0)} s away, but the warm-up needs `
      + `${needed / 1000} s: run k6.sh with a longer --lead`);
  }
  console.log(`setup: drop in ${(untilDrop / 1000).toFixed(1)} s; ${config.vus} buyers for ${hot.length} seats`);
  return { tokens, hot, warm };
}

/** Warm-up: hold one free seat of the warm-up event and release it. Not measured, not gated beyond 5xx. */
export function warmup(data) {
  const token = data.tokens[config.vus + (exec.scenario.iterationInTest % config.warmupUsers)];
  const { res, bytes } = fetchAvailability(config.warmupEventId, token, 'warmup-seatmap');
  if (!bytes) {
    unexpected('warm-up seatmap', res);
    return;
  }
  const free = pickRandom(data.warm.filter((s) => isAvailable(bytes, s.bit)), 1);
  if (free.length === 0) {
    return;
  }
  const hold = post(`/events/${config.warmupEventId}/holds`, { seatIds: [free[0].id] }, 'warmup-hold', token);
  if (hold.status === 201) {
    del(`/holds/${body(hold).holdId}`, 'warmup-release', token);
  } else if (hold.status >= 500 || hold.status === 0) {
    unexpected('warm-up hold', hold);
  }
}

/** Sleeps until the drop by the server's clock, polling the event in its last seconds (the herd). */
function waitForDrop() {
  for (;;) {
    const res = get(`/events/${config.benchEventId}`, 'event-poll');
    const event = body(res);
    if (res.status !== 200 || !event) {
      unexpected('GET /events/{id} before the drop', res);
      sleep(1);
      continue;
    }
    const drop = Date.parse(event.dropAt);
    const server = Date.parse(event.serverTime);
    const left = drop - server;
    if (left <= 0) {
      // Server time minus local time, so later timings can be put on the server's clock.
      return { drop, offset: server - Date.now() };
    }
    // Far away: sleep most of it in one go (spread over 1 s so the herd doesn't poll in lockstep);
    // close: poll every 100-300 ms.
    sleep(left > 3000 ? (left - 2000 + Math.random() * 1000) / 1000 : 0.1 + Math.random() * 0.2);
  }
}

function holdCode(res) {
  if (res.status === 201) {
    return 'HELD';
  }
  const c = code(res);
  return HOLD_CODES.includes(c) ? c : 'OTHER';
}

export function collide(data) {
  const token = data.tokens[exec.scenario.iterationInTest];
  const { drop, offset } = waitForDrop();
  const msAfterDrop = () => Date.now() + offset - drop;

  for (let attempt = 1; attempt <= config.maxTries; attempt++) {
    const { res: mapRes, bytes } = fetchAvailability(config.benchEventId, token);
    if (!bytes) {
      unexpected('GET seatmap', mapRes);
      return;
    }
    const free = data.hot.filter((s) => isAvailable(bytes, s.bit));
    if (free.length === 0) {
      buyersFoundBlockGone.add(1);
      return;
    }
    const wanted = pickRandom(free, 1 + Math.floor(Math.random() * 4));
    attemptAfterDrop.add(msAfterDrop());
    const hold = post(`/events/${config.benchEventId}/holds`, { seatIds: wanted.map((s) => s.id) }, 'hold', token);
    const answer = holdCode(hold);
    holdAnswers[answer].add(1);
    holdDuration.add(hold.timings.duration);

    if (answer === 'HELD') {
      holdDurationWon.add(hold.timings.duration);
      wonAfterDrop.add(msAfterDrop());
      seatsHeld.add(wanted.length);
      const order = post('/orders', { holdId: body(hold).holdId }, 'order', token);
      orderDuration.add(order.timings.duration);
      if (order.status === 201 && body(order) && body(order).status === 'PAID') {
        seatsSold.add(wanted.length);
      } else {
        // HOLD_EXPIRED, SEAT_TAKEN or PAYMENT_FAILED can't happen here (a fresh hold, the fake provider):
        // each one would be worth a look.
        unexpected('POST /orders', order);
      }
      return;
    }
    holdDurationLost.add(hold.timings.duration);
    if (answer === 'SEAT_TAKEN' || answer === 'SEAT_CONTENDED') {
      sleep(0.05 + Math.random() * 0.2);
      continue;
    }
    // SALE_NOT_OPEN (we waited for the drop by the server's clock), LIMIT_EXCEEDED (one hold per buyer),
    // 5xx and timeouts are all unexpected.
    unexpected(`POST hold (${answer})`, hold);
    return;
  }
  buyersGaveUp.add(1);
}

export function teardown() {
  verdict([config.warmupEventId, config.benchEventId]);
}

export function handleSummary(data) {
  const attempts = num(data, 'hold_duration');
  const conflicts = num(data, 'hold_SEAT_TAKEN') + num(data, 'hold_SEAT_CONTENDED');
  const windowMs = num(data, 'hold_attempt_ms_after_drop', 'max') - num(data, 'hold_attempt_ms_after_drop', 'min');
  const holdsPerSecond = windowMs > 0 ? (attempts / (windowMs / 1000)).toFixed(0) : '-';
  const answers = HOLD_CODES.map((c) => `${c} ${num(data, `hold_${c}`)}`).join(', ');
  return writeSummary(data, `seat-collision ${config.strategy}: ${config.vus} buyers, `
    + `${config.hotSeats} seats (${config.runId})`, [
    `hold latency, all:  ${latency(data, 'hold_duration')}`,
    `hold latency, won:  ${latency(data, 'hold_duration_won')}`,
    `hold latency, lost: ${latency(data, 'hold_duration_lost')}`,
    `order latency:      ${latency(data, 'order_duration')}`,
    `hold answers: ${answers}`,
    `conflict rate: ${attempts ? ((conflicts / attempts) * 100).toFixed(1) : '-'} % of ${attempts} attempts; `
      + `${holdsPerSecond} holds/s over ${(windowMs / 1000).toFixed(2)} s`,
    `seats held ${num(data, 'seats_held')}, sold ${num(data, 'seats_sold')} of ${config.hotSeats}; `
      + `last seat won ${(num(data, 'hold_won_ms_after_drop', 'max') / 1000).toFixed(2)} s after the drop`,
    `buyers: ${num(data, 'buyers_found_block_gone')} found the block gone, `
      + `${num(data, 'buyers_gave_up')} gave up after ${config.maxTries} tries`,
    `unexpected responses: ${num(data, 'unexpected_responses')}`,
    `verdict: ${num(data, 'invariant_violations')} invariant violations, `
      + `conclusive ${(num(data, 'verdict_conclusive', 'rate') * 100).toFixed(0)} %`,
  ], {
    UNEXPECTED: num(data, 'unexpected_responses'),
    VIOLATIONS: num(data, 'invariant_violations'),
    DB_CHECK_EVENT: 'bench',
    SEATS_SOLD: num(data, 'seats_sold'),
  });
}
