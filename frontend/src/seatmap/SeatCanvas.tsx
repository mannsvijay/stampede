import { type MouseEvent, type RefObject, useEffect, useMemo, useRef, useState } from 'react'

import type { EventLayout } from '../api/client'
import { drawVenue, readPalette, type SeatState } from './draw'
import { CANVAS_PADDING as PADDING, fitTransform, hitRadius, seatAt, toVenue } from './geometry'

interface SeatCanvasProps {
  layout: EventLayout
  /** One entry per `layout.seats[i]`. A new array means "redraw". */
  states: readonly SeatState[]
  onSeatClick: (seatIndex: number) => void
  label: string
}

/**
 * The venue drawn on a canvas, as wide as its container, with the venue's proportions. Clicking near
 * a seat reports its index. A canvas is one image to assistive technology, so the page also offers
 * a keyboard seat picker and a list of the picked seats.
 */
export function SeatCanvas({ layout, states, onSeatClick, label }: SeatCanvasProps) {
  const containerRef = useRef<HTMLDivElement>(null)
  const canvasRef = useRef<HTMLCanvasElement>(null)
  const cssWidth = useElementWidth(containerRef)
  const dpr = useDevicePixelRatio()
  const { width: venueWidth, height: venueHeight } = layout.venue
  const cssHeight = venueWidth > 0 ? Math.round((cssWidth * venueHeight) / venueWidth) : 0
  const transform = useMemo(
    () => fitTransform(venueWidth, venueHeight, cssWidth, cssHeight, PADDING),
    [venueWidth, venueHeight, cssWidth, cssHeight],
  )

  useEffect(() => {
    const canvas = canvasRef.current
    if (!canvas || cssWidth === 0) {
      return
    }
    // Device pixels, not CSS pixels: on a 2x screen a 600 px wide canvas gets 1200 pixels, so it
    // stays sharp instead of being upscaled by the browser.
    canvas.width = Math.round(cssWidth * dpr)
    canvas.height = Math.round(cssHeight * dpr)
    const ctx = canvas.getContext('2d')
    if (!ctx) {
      return // no canvas support (jsdom in tests): the picker and list still work
    }
    drawVenue(ctx, layout, (i) => states[i] ?? 'taken', transform, dpr, readPalette(canvas))
  }, [layout, states, transform, cssWidth, cssHeight, dpr])

  function onClick(event: MouseEvent<HTMLCanvasElement>) {
    // Measure the canvas as it is on screen now, so the click maps to exactly what was drawn.
    const rect = event.currentTarget.getBoundingClientRect()
    const t = fitTransform(venueWidth, venueHeight, rect.width, rect.height, PADDING)
    if (t.scale === 0) {
      return
    }
    const point = toVenue(t, event.clientX - rect.left, event.clientY - rect.top)
    const index = seatAt(layout.seats, point, hitRadius(layout.venue.seatRadius, t))
    if (index >= 0) {
      onSeatClick(index)
    }
  }

  return (
    <div ref={containerRef}>
      <canvas
        ref={canvasRef}
        role="img"
        aria-label={label}
        onClick={onClick}
        style={{ display: 'block', width: cssWidth, height: cssHeight, cursor: 'pointer' }}
      />
    </div>
  )
}

/** The element's content width in CSS px, kept up to date by a ResizeObserver (0 where unsupported). */
function useElementWidth(ref: RefObject<HTMLElement | null>): number {
  const [width, setWidth] = useState(0)
  useEffect(() => {
    const element = ref.current
    if (!element || typeof ResizeObserver === 'undefined') {
      return
    }
    // The observer also reports the initial size right after observe().
    const observer = new ResizeObserver((entries) => {
      const entry = entries[0]
      if (entry) {
        setWidth(Math.floor(entry.contentRect.width))
      }
    })
    observer.observe(element)
    return () => observer.disconnect()
  }, [ref])
  return width
}

/**
 * window.devicePixelRatio, updated when it changes (the window moves to another screen, or the user
 * zooms the page). Resizing doesn't report that, so a media query on the current ratio is watched.
 */
function useDevicePixelRatio(): number {
  const [dpr, setDpr] = useState(() => window.devicePixelRatio || 1)
  useEffect(() => {
    if (typeof window.matchMedia !== 'function') {
      return
    }
    const query = window.matchMedia(`(resolution: ${dpr}dppx)`)
    const onChange = () => setDpr(window.devicePixelRatio || 1)
    query.addEventListener('change', onChange)
    return () => query.removeEventListener('change', onChange)
  }, [dpr])
  return dpr
}
