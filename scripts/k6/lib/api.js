// Thin HTTP helpers over the Stampede API (M2.4a). Every request carries a `name` tag so the summary can
// report per endpoint; problem+json bodies (PRD 10) are reduced to their stable `code`.
import http from 'k6/http';
import { config } from './config.js';

// 2xx, 409 (SEAT_TAKEN, SEAT_CONTENDED, EMAIL_TAKEN, ...) and 410 (HOLD_EXPIRED) are answers, not
// transport failures; whether an answer was expected is judged by each scenario (unexpected_responses).
http.setResponseCallback(http.expectedStatuses({ min: 200, max: 299 }, 409, 410));

const JSON_HEADERS = { 'Content-Type': 'application/json' };

function headers(token) {
  return token ? { ...JSON_HEADERS, Authorization: `Bearer ${token}` } : JSON_HEADERS;
}

export function get(path, name, token, extraTags = {}) {
  return http.get(`${config.baseUrl}${path}`, { headers: headers(token), tags: { name, ...extraTags } });
}

export function post(path, body, name, token, extraTags = {}) {
  return http.post(`${config.baseUrl}${path}`, JSON.stringify(body),
    { headers: headers(token), tags: { name, ...extraTags } });
}

export function del(path, name, token, extraTags = {}) {
  return http.del(`${config.baseUrl}${path}`, null, { headers: headers(token), tags: { name, ...extraTags } });
}

/** The parsed JSON body, or null (empty body, HTML error page, timeout). */
export function body(res) {
  try {
    return res.body ? JSON.parse(res.body) : null;
  } catch (e) {
    return null;
  }
}

/** The problem+json `code`, or a stand-in: TIMEOUT (status 0), HTTP_<status> without a code, OK for 2xx. */
export function code(res) {
  if (res.status === 0) {
    return 'TIMEOUT';
  }
  if (res.status >= 200 && res.status < 300) {
    return 'OK';
  }
  const parsed = body(res);
  return (parsed && parsed.code) || `HTTP_${res.status}`;
}

export function login(email, password, name = 'setup-login') {
  const res = post('/auth/login', { email, password }, name);
  if (res.status !== 200) {
    throw new Error(`login of ${email} failed: ${res.status} ${code(res)}`);
  }
  return body(res).accessToken;
}
