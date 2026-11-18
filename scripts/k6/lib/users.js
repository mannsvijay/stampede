// Test users and their access tokens (M2.4a, D-024 decision D2-A): setup() registers them (EMAIL_TAKEN
// from an earlier run is fine) and logs every one in, before anything is measured. Login checks a
// bcrypt hash (cost 12, deliberately slow, M0.5), so this costs real CPU; it runs in parallel batches,
// and the measured phase starts only after it (and a warm-up) are done.
import http from 'k6/http';
import { config } from './config.js';
import { body } from './api.js';

const BATCH = 20;

export function userEmail(i) {
  return `k6-user-${String(i + 1).padStart(5, '0')}@load.test`;
}

function batched(count, makeRequest, check) {
  for (let start = 0; start < count; start += BATCH) {
    const requests = [];
    for (let i = start; i < Math.min(count, start + BATCH); i++) {
      requests.push(makeRequest(i));
    }
    const responses = http.batch(requests);
    responses.forEach((res, k) => check(start + k, res));
  }
}

function request(path, email, name) {
  return {
    method: 'POST',
    url: `${config.baseUrl}${path}`,
    body: JSON.stringify({ email, password: config.userPassword }),
    params: { headers: { 'Content-Type': 'application/json' }, tags: { name } },
  };
}

/** Access tokens of users 0 .. count-1, in that order. Throws if any user can't be registered or logged in. */
export function prepareUsers(count) {
  const started = Date.now();
  batched(count, (i) => request('/auth/register', userEmail(i), 'setup-register'), (i, res) => {
    if (res.status !== 201 && res.status !== 409) {
      throw new Error(`registering ${userEmail(i)} failed: ${res.status} ${res.body}`);
    }
  });
  const tokens = new Array(count);
  batched(count, (i) => request('/auth/login', userEmail(i), 'setup-login'), (i, res) => {
    const parsed = body(res);
    if (res.status !== 200 || !parsed || !parsed.accessToken) {
      throw new Error(`logging in ${userEmail(i)} failed: ${res.status} ${res.body}`);
    }
    tokens[i] = parsed.accessToken;
  });
  console.log(`setup: ${count} users registered and logged in in ${((Date.now() - started) / 1000).toFixed(1)} s`);
  return tokens;
}
