// Registers the load-test admin k6-admin@load.test (M2.4a, D-024), run by scripts/k6.sh before every
// scenario. EMAIL_TAKEN (an earlier run) is fine. k6.sh then gives it the ADMIN role in SQL, as the API
// tests do: the API has no way to make an admin (only the bootstrap-admin profile, from .env). Its
// password is random, made once by k6.sh and kept in benchmarks/runs/.k6-admin-password (gitignored).
import http from 'k6/http';
import exec from 'k6/execution';

export const options = { vus: 1, iterations: 1, summaryTrendStats: ['avg'] };

export default function () {
  const res = http.post(`${__ENV.BASE_URL}/auth/register`,
    JSON.stringify({ email: __ENV.ADMIN_EMAIL, password: __ENV.ADMIN_PASSWORD }),
    { headers: { 'Content-Type': 'application/json' }, responseCallback: http.expectedStatuses(201, 409) });
  if (res.status !== 201 && res.status !== 409) {
    exec.test.abort(`registering ${__ENV.ADMIN_EMAIL} failed: ${res.status} ${res.body}`);
  }
  console.log(`prepare: ${__ENV.ADMIN_EMAIL} ${res.status === 201 ? 'registered' : 'already registered'}`);
}

export function handleSummary() {
  return {};
}
