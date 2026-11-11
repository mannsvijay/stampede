/**
 * Decodes the seat map's `available` bitset (GET /events/{id}/seatmap, D-020).
 *
 * The server packs one bit per seat, in the order of the layout's `seats` list, most significant
 * bit first, and base64-encodes the bytes: seat i is bit (7 - i % 8) of byte floor(i / 8), and 1 means
 * AVAILABLE. 2,014 seats fit in 252 bytes, against about 110 KB as a JSON list of {id, status}.
 */
export function decodeAvailability(base64: string, seatCount: number): boolean[] {
  const binary = atob(base64)
  const bytesNeeded = Math.ceil(seatCount / 8)
  if (binary.length !== bytesNeeded) {
    // The layout and the seat map disagree (e.g. a stale cached layout): drawing would be wrong.
    throw new Error(`Seat map has ${binary.length} bytes, expected ${bytesNeeded} for ${seatCount} seats`)
  }
  const available = new Array<boolean>(seatCount)
  for (let i = 0; i < seatCount; i++) {
    available[i] = (binary.charCodeAt(i >> 3) & (0x80 >> (i & 7))) !== 0
  }
  return available
}
