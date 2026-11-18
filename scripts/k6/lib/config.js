// Settings every scenario reads (M2.4a, D-024). scripts/k6.sh passes them as environment variables;
// run a scenario through k6.sh, not with a bare `k6 run`, so the events and the admin exist.

function required(name) {
  const value = __ENV[name];
  if (!value) {
    throw new Error(`${name} is not set; run the scenario through scripts/k6.sh`);
  }
  return value;
}

function int(name, fallback) {
  const raw = __ENV[name];
  if (raw === undefined || raw === '') {
    return fallback;
  }
  const value = Number(raw);
  if (!Number.isInteger(value) || value < 0) {
    throw new Error(`${name} must be a whole number, was '${raw}'`);
  }
  return value;
}

export const config = {
  baseUrl: __ENV.BASE_URL || 'http://backend:8080',
  runId: required('RUN_ID'),
  strategy: required('STRATEGY'),
  // The event the measured phase runs on: created PESSIMISTIC with its drop in the future, switched to
  // STRATEGY by setup() through POST /admin/events/{id}/strategy (D-023).
  benchEventId: required('BENCH_EVENT_ID'),
  // Already on sale, created with STRATEGY: warm-up traffic, never measured.
  warmupEventId: required('WARMUP_EVENT_ID'),
  adminEmail: required('ADMIN_EMAIL'),
  adminPassword: required('ADMIN_PASSWORD'),
  // Test users k6-user-00001@load.test ...; one password for all (they hold no privileges).
  userPassword: __ENV.USER_PASSWORD || 'k6-load-test-password',
  vus: int('VUS', 500),
  hotSeats: int('HOT_SEATS', 100),
  maxTries: int('MAX_TRIES', 5),
  warmupSeconds: int('WARMUP_SECONDS', 30),
  warmupRate: int('WARMUP_RATE', 10),
  warmupUsers: int('WARMUP_USERS', 20),
  // How long teardown() waits for the event's stream lag to reach zero before giving up on a verdict.
  verdictTimeoutSeconds: int('VERDICT_TIMEOUT_SECONDS', 120),
};
