import { describe, expect, it } from 'vitest'

import { fitTransform, hitRadius, seatAt, toVenue } from './geometry'

describe('fitTransform', () => {
  it('a canvas wider than the venue: limited by height, centred horizontally', () => {
    // Venue 1200 x 700 into 1000 x 350: height limits (350 / 700 = 0.5), width used = 600.
    const t = fitTransform(1200, 700, 1000, 350)
    expect(t.scale).toBe(0.5)
    expect(t.offsetX).toBe(200) // (1000 - 600) / 2
    expect(t.offsetY).toBe(0)
  })

  it('a canvas taller than the venue: limited by width, centred vertically', () => {
    // Venue 1200 x 700 into 600 x 600: width limits (0.5), height used = 350.
    const t = fitTransform(1200, 700, 600, 600)
    expect(t.scale).toBe(0.5)
    expect(t.offsetX).toBe(0)
    expect(t.offsetY).toBe(125) // (600 - 350) / 2
  })

  it('keeps the padding free on the limiting side', () => {
    const t = fitTransform(100, 50, 220, 200, 10)
    expect(t.scale).toBe(2) // (220 - 20) / 100
    expect(t.offsetX).toBe(10)
  })

  it('a canvas with no size yet gives scale 0 instead of NaN', () => {
    expect(fitTransform(1200, 700, 0, 0).scale).toBe(0)
  })
})

describe('toVenue', () => {
  it('undoes the drawing transform: the venue corners land back on themselves', () => {
    const t = fitTransform(1200, 700, 1000, 350)
    const toCss = (x: number, y: number) => [x * t.scale + t.offsetX, y * t.scale + t.offsetY] as const

    const corners: [number, number][] = [
      [0, 0],
      [1200, 700],
      [390, 130],
    ]
    for (const [x, y] of corners) {
      const [cssX, cssY] = toCss(x, y)
      expect(toVenue(t, cssX, cssY)).toEqual({ x, y })
    }
  })
})

describe('seatAt', () => {
  const seats = [
    { x: 10, y: 10 },
    { x: 20, y: 10 },
    { x: 10, y: 30 },
  ]

  it('finds the seat under the point', () => {
    expect(seatAt(seats, { x: 11, y: 9 }, 4)).toBe(0)
    expect(seatAt(seats, { x: 10, y: 33 }, 4)).toBe(2)
  })

  it('returns -1 when no seat centre is within the radius', () => {
    expect(seatAt(seats, { x: 15, y: 20 }, 4)).toBe(-1)
  })

  it('picks the nearest seat when two are within reach', () => {
    expect(seatAt(seats, { x: 16, y: 10 }, 10)).toBe(1) // 6 from seat 0, 4 from seat 1
  })

  it('handles an empty venue', () => {
    expect(seatAt([], { x: 0, y: 0 }, 4)).toBe(-1)
  })
})

describe('hitRadius', () => {
  it('is the seat radius when seats are drawn large enough', () => {
    expect(hitRadius(4, { scale: 3, offsetX: 0, offsetY: 0 })).toBe(4) // 8 css px = 2.7 units < 4
  })

  it('grows to 8 CSS pixels when zoomed out, so small seats stay clickable', () => {
    expect(hitRadius(4, { scale: 0.5, offsetX: 0, offsetY: 0 })).toBe(16) // 8 / 0.5
  })
})
