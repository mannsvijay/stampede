// Steps every scenario shares (M2.4a, D-024): switching the benchmark event to the strategy under test
// before its drop, and the verdict after the run.
import { sleep } from 'k6';
import { Counter, Rate } from 'k6/metrics';
import { config } from './config.js';
import { get, post, body, code, login } from './api.js';

// Gated by every scenario's thresholds (correctness only, D5-A).
export const unexpectedResponses = new Counter('unexpected_responses');
export const invariantViolations = new Counter('invariant_violations');
export const verdictConclusive = new Rate('verdict_conclusive');

export const CORRECTNESS_THRESHOLDS = {
  unexpected_responses: ['count==0'],
  invariant_violations: ['count==0'],
  verdict_conclusive: ['rate==1'],
};

/** Counts an answer the scenario didn't expect and logs it (the first few of each kind are enough). */
const logged = {};
export function unexpected(what, res) {
  unexpectedResponses.add(1, { what, code: code(res) });
  const key = `${what} ${code(res)}`;
  logged[key] = (logged[key] || 0) + 1;
  if (logged[key] <= 3) {
    console.warn(`unexpected: ${key} (status ${res.status}) ${String(res.body).slice(0, 300)}`);
  }
}

/** Gives the threshold metrics a first sample, so a clean run reports 0 rather than "no data". */
export function initThresholdMetrics() {
  unexpectedResponses.add(0);
  invariantViolations.add(0);
}

/**
 * POST /admin/events/{id}/strategy on the benchmark event (M2.3a, D-023). It was created PESSIMISTIC with
 * its drop in the future and has never had a hold, so the switch is always allowed; anything else stops
 * the run. Switching to REDIS_FIRST also loads the event into Redis, so the first holds don't pay for it.
 */
export function switchBenchEvent(adminToken) {
  const res = post(`/admin/events/${config.benchEventId}/strategy`, { strategy: config.strategy },
    'setup-switch', adminToken);
  const parsed = body(res);
  if (res.status !== 200 || !parsed || parsed.strategy !== config.strategy) {
    throw new Error(`switching the benchmark event to ${config.strategy} failed: ${res.status} ${res.body}`);
  }
  console.log(`setup: benchmark event ${config.benchEventId} is ${parsed.strategy} (was ${parsed.previous})`);
}

/** Milliseconds from now until the benchmark event's drop, by the server's clock. */
export function msUntilDrop() {
  const res = get(`/events/${config.benchEventId}`, 'setup-event');
  const event = body(res);
  if (res.status !== 200 || !event) {
    throw new Error(`reading the benchmark event failed: ${res.status}`);
  }
  return Date.parse(event.dropAt) - Date.parse(event.serverTime);
}

/**
 * The verdict for each event (teardown, after every VU has finished). For each one it asks
 * GET /admin/invariants?grace=PT0S until unappliedStreamEntries is false (the consumer has written every
 * Redis hold of the event to Postgres; always false for a Postgres strategy), then counts that answer's
 * violations.
 *
 * Grace PT0S (strict), the same as the test suite: k6 has had every response before teardown runs,
 * checkout is synchronous (payment and tickets inside POST /orders), so no order is legitimately half
 * done; and with the stream drained, Redis and Postgres must agree exactly. Anything reported then is a
 * real violation, not an in-between state. (A request k6 gave up on, status 0, could still be running on
 * the server; such runs already fail on unexpected_responses.)
 */
export function verdict(eventIds) {
  const token = login(config.adminEmail, config.adminPassword, 'verdict-login');
  for (const eventId of eventIds) {
    const deadline = Date.now() + config.verdictTimeoutSeconds * 1000;
    let answer = null;
    let lagging = 0;
    while (Date.now() < deadline) {
      const res = get(`/admin/invariants?eventId=${eventId}&grace=PT0S`, 'verdict', token);
      const parsed = body(res);
      if (res.status !== 200 || !parsed) {
        console.error(`verdict: GET /admin/invariants for ${eventId} answered ${res.status} ${res.body}`);
        break;
      }
      if (!parsed.unappliedStreamEntries) {
        answer = parsed;
        break;
      }
      lagging++;
      sleep(1);
    }
    if (answer === null) {
      verdictConclusive.add(false);
      console.error(`verdict: no conclusive answer for event ${eventId} within ${config.verdictTimeoutSeconds} s`);
      continue;
    }
    verdictConclusive.add(true);
    invariantViolations.add(answer.violations.length);
    console.log(`verdict: event ${eventId} (${answer.strategy}): ${answer.violations.length} violations, `
      + `grace ${answer.grace}, after ${lagging} s of stream lag, checked at ${answer.checkedAt}`);
    answer.violations.forEach((v) => console.error(`verdict: ${v.rule} ${v.name}: ${v.detail}`));
  }
}
