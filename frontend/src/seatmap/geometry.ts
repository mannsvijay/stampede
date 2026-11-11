/**
 * The maths between the venue's coordinates and the canvas, kept free of React and Canvas so it can
 * be tested directly. Venue units: origin top-left, y grows downwards, like Canvas2D
 * (docs/venue-format.md), so fitting needs only a uniform scale and an offset.
 */

/** Maps venue units to CSS pixels: `css = venue * scale + offset`. */
export interface Transform {
  scale: number
  offsetX: number
  offsetY: number
}

export interface Point {
  x: number
  y: number
}

/**
 * Scales the whole venue to fit inside the canvas (keeping its proportions) and centres it, leaving
 * `padding` CSS pixels free on every side. A canvas with a different shape gets empty bars on two sides.
 */
export function fitTransform(
  venueWidth: number,
  venueHeight: number,
  cssWidth: number,
  cssHeight: number,
  padding = 0,
): Transform {
  const usableWidth = Math.max(0, cssWidth - 2 * padding)
  const usableHeight = Math.max(0, cssHeight - 2 * padding)
  const scale = venueWidth > 0 && venueHeight > 0 ? Math.min(usableWidth / venueWidth, usableHeight / venueHeight) : 0
  return {
    scale,
    offsetX: (cssWidth - venueWidth * scale) / 2,
    offsetY: (cssHeight - venueHeight * scale) / 2,
  }
}

/** The venue point under a CSS pixel of the canvas (the inverse of drawing). */
export function toVenue(transform: Transform, cssX: number, cssY: number): Point {
  return { x: (cssX - transform.offsetX) / transform.scale, y: (cssY - transform.offsetY) / transform.scale }
}

/**
 * How close (in venue units) a click must be to a seat's centre to pick it: the seat's own radius,
 * but never less than `minCssPx` on screen, so tiny seats on a small screen stay clickable.
 */
export function hitRadius(seatRadius: number, transform: Transform, minCssPx = 8): number {
  return transform.scale > 0 ? Math.max(seatRadius, minCssPx / transform.scale) : seatRadius
}

/**
 * Index of the seat whose centre is nearest to `point`, if it is within `radius`; otherwise -1.
 * A linear scan: about 2,000 comparisons for the demo venue, well under a millisecond. A 20,000-seat
 * venue (PRD 8.4) would want a grid index.
 */
export function seatAt(seats: readonly Point[], point: Point, radius: number): number {
  let best = -1
  let bestDistance = radius * radius
  seats.forEach((seat, i) => {
    const dx = seat.x - point.x
    const dy = seat.y - point.y
    const distance = dx * dx + dy * dy
    if (distance <= bestDistance) {
      best = i
      bestDistance = distance
    }
  })
  return best
}
