// smoke (M2.4a, D-024): one buyer, one pass over every endpoint the load scenarios use, on the warm-up
// event (already on sale). Checks the stack, the scripts and the verdict in seconds, before a long run.
//   ./scripts/k6.sh smoke --strategy REDIS_FIRST
import { check } from 'k6';
import { Counter } from 'k6/metrics';
import { config } from './lib/config.js';
import { get, post, del, body, code, login } from './lib/api.js';
import { prepareUsers } from './lib/users.js';
import { loadLayout, fetchAvailability, isAvailable } from './lib/seatmap.js';
import {
  CORRECTNESS_THRESHOLDS, initThresholdMetrics, switchBenchEvent, unexpected, verdict,
} from './lib/run.js';
import { writeSummary, latency, num } from './lib/summary.js';

const seatsSold = new Counter('seats_sold');

export const options = {
  scenarios: {
    smoke: { executor: 'per-vu-iterations', vus: 1, iterations: 1, maxDuration: '2m' },
  },
  thresholds: {
    ...CORRECTNESS_THRESHOLDS,
    checks: ['rate==1'],
  },
  setupTimeout: '5m',
  teardownTimeout: '5m',
  summaryTrendStats: ['avg', 'min', 'med', 'p(95)', 'p(99)', 'max', 'count'],
};

export function setup() {
  initThresholdMetrics();
  switchBenchEvent(login(config.adminEmail, config.adminPassword));
  return { token: prepareUsers(1)[0] };
}

function expectStatus(res, status, what) {
  const ok = check(res, { [`${what} is ${status}`]: (r) => r.status === status });
  if (!ok) {
    unexpected(what, res);
  }
  return ok;
}

export default function (data) {
  const event = config.warmupEventId;
  expectStatus(get('/events', 'events'), 200, 'GET /events');
  expectStatus(get(`/events/${event}`, 'event'), 200, 'GET /events/{id}');
  const layout = loadLayout(event);
  console.log(`smoke: layout ${layout.seats.length} seats, ${layout.bytes} bytes, ${layout.durationMs.toFixed(1)} ms`);
  const { res: mapRes, bytes } = fetchAvailability(event, data.token);
  if (!expectStatus(mapRes, 200, 'GET seatmap')) {
    return;
  }
  const free = layout.seats.filter((s) => isAvailable(bytes, s.bit));
  check(free, { 'the warm-up event has at least 2 free seats': (f) => f.length >= 2 });

  // Hold one seat and buy it.
  const hold = post(`/events/${event}/holds`, { seatIds: [free[0].id] }, 'hold', data.token);
  if (expectStatus(hold, 201, 'POST hold')) {
    const order = post('/orders', { holdId: body(hold).holdId }, 'order', data.token);
    if (expectStatus(order, 201, 'POST /orders')) {
      if (check(body(order), { 'the order is PAID': (o) => o.status === 'PAID' })) {
        seatsSold.add(1);
      }
    }
  }
  // Hold another and release it.
  const second = post(`/events/${event}/holds`, { seatIds: [free[1].id] }, 'hold', data.token);
  if (expectStatus(second, 201, 'POST hold (second)')) {
    expectStatus(del(`/holds/${body(second).holdId}`, 'release', data.token), 204, 'DELETE hold');
  }
  // A seat that is now sold: SEAT_TAKEN, the answer a losing buyer gets.
  const taken = post(`/events/${event}/holds`, { seatIds: [free[0].id] }, 'hold', data.token);
  check(taken, { 'a sold seat is SEAT_TAKEN': (r) => r.status === 409 && code(r) === 'SEAT_TAKEN' })
    || unexpected('POST hold on a sold seat', taken);
}

export function teardown() {
  verdict([config.warmupEventId, config.benchEventId]);
}

export function handleSummary(data) {
  return writeSummary(data, `smoke ${config.strategy} (${config.runId})`, [
    `requests: ${num(data, 'http_reqs')}, checks passed: ${(num(data, 'checks', 'rate') * 100).toFixed(1)} %`,
    `latency all requests: ${latency(data, 'http_req_duration')}`,
    `unexpected responses: ${num(data, 'unexpected_responses')}`,
    `verdict: ${num(data, 'invariant_violations')} invariant violations, `
      + `conclusive ${(num(data, 'verdict_conclusive', 'rate') * 100).toFixed(0)} %`,
  ], {
    UNEXPECTED: num(data, 'unexpected_responses'),
    VIOLATIONS: num(data, 'invariant_violations'),
    DB_CHECK_EVENT: 'warmup',
    SEATS_SOLD: num(data, 'seats_sold'),
  });
}
