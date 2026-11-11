import { describe, expect, it } from 'vitest'

import { decodeAvailability } from './availability'

describe('decodeAvailability', () => {
  it('reads the most significant bit first (the vector SeatMapApiTest checks on the server)', () => {
    // 5 seats, all available: 1111 1000 = 0xF8, base64 "+A==".
    expect(decodeAvailability('+A==', 5)).toEqual([true, true, true, true, true])
  })

  it('maps bit i to seat i across byte boundaries', () => {
    // 0b1010_0000, 0b0100_0000: seats 0, 2 and 9 available.
    const base64 = btoa(String.fromCharCode(0b1010_0000, 0b0100_0000))
    const available = decodeAvailability(base64, 10)
    expect(available.flatMap((free, i) => (free ? [i] : []))).toEqual([0, 2, 9])
  })

  it('handles an event without seats', () => {
    expect(decodeAvailability('', 0)).toEqual([])
  })

  it('rejects a bitset whose length does not match the seat count', () => {
    expect(() => decodeAvailability('+A==', 9)).toThrow(/expected 2/)
  })
})
