// The seat layout and the availability bitset (PRD 10, D-020). GET /events/{id}/layout lists every seat,
// sorted by seat id; GET /events/{id}/seatmap answers one bit per seat in that order, base64, most
// significant bit first: bit i is bit (7 - i % 8) of byte floor(i / 8), 1 = AVAILABLE.
import encoding from 'k6/encoding';
import { get, body } from './api.js';

/** The layout's seats ({id, section, row, number, bit}), or throws. Also reports the body size. */
export function loadLayout(eventId, name = 'layout', tags = {}) {
  const res = get(`/events/${eventId}/layout`, name, null, tags);
  const layout = body(res);
  if (res.status !== 200 || !layout) {
    throw new Error(`layout of ${eventId}: ${res.status}`);
  }
  return {
    bytes: res.body.length,
    durationMs: res.timings.duration,
    seats: layout.seats.map((seat, bit) => ({
      id: seat.id, section: seat.section, row: seat.row, number: seat.number, bit,
    })),
  };
}

/**
 * The first `count` seats in reading order (section code, then row, then number): a block of
 * neighbouring seats, the way a crowd goes for the same good seats. Deterministic, so every run of an
 * event copied from the same venue targets the same block.
 */
export function hotBlock(seats, count) {
  const sorted = [...seats].sort((a, b) => a.section.localeCompare(b.section)
    || a.row.localeCompare(b.row, undefined, { numeric: true })
    || a.number - b.number);
  if (sorted.length < count) {
    throw new Error(`the venue has ${sorted.length} seats, fewer than ${count}`);
  }
  return sorted.slice(0, count);
}

/** The availability bytes of GET /events/{id}/seatmap, or null if it failed. */
export function fetchAvailability(eventId, token, name = 'seatmap', tags = {}) {
  const res = get(`/events/${eventId}/seatmap`, name, token, tags);
  const parsed = body(res);
  if (res.status !== 200 || !parsed) {
    return { res, bytes: null };
  }
  return { res, bytes: new Uint8Array(encoding.b64decode(parsed.available, 'std')) };
}

export function isAvailable(bytes, bit) {
  return ((bytes[bit >> 3] >> (7 - (bit & 7))) & 1) === 1;
}

/** `count` distinct random elements of `items` (all of them if there are fewer). */
export function pickRandom(items, count) {
  const copy = [...items];
  for (let i = copy.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1));
    [copy[i], copy[j]] = [copy[j], copy[i]];
  }
  return copy.slice(0, Math.min(count, copy.length));
}
