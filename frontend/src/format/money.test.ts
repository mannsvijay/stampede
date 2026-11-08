import { describe, expect, it } from 'vitest'

import { formatPaise } from './money'

describe('formatPaise', () => {
  it.each([
    [450_000, '₹4,500'],
    [120_000, '₹1,200'],
    [0, '₹0'],
    [123_450, '₹1,234.50'],
    [12_345_678, '₹1,23,456.78'], // Indian digit grouping
  ])('%i paise → %s', (paise, expected) => {
    expect(formatPaise(paise)).toBe(expected)
  })

  it('rejects a non-integer amount instead of rounding it silently', () => {
    expect(() => formatPaise(10.5)).toThrow(RangeError)
  })
})
