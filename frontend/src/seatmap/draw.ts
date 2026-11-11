import type { EventLayout } from '../api/client'
import type { Transform } from './geometry'

export type SeatState = 'available' | 'taken' | 'mine'

export interface Palette {
  background: string
  foreground: string
  muted: string
  signal: string
}

/**
 * Draws the whole venue. Called again on every change: about 2,000 circles take well under a frame.
 * (PRD 8.4's 20,000 seats at 60 fps with pan and zoom is M5 work.)
 *
 * `dpr` is window.devicePixelRatio. The canvas has dpr times as many pixels as CSS pixels, and this
 * transform maps venue units straight to device pixels, so lines and circles are sharp on high-DPI
 * screens instead of being drawn small and stretched.
 */
export function drawVenue(
  ctx: CanvasRenderingContext2D,
  layout: EventLayout,
  stateOf: (seatIndex: number) => SeatState,
  transform: Transform,
  dpr: number,
  palette: Palette,
): void {
  const { venue, seats } = layout
  ctx.setTransform(1, 0, 0, 1, 0, 0)
  ctx.fillStyle = palette.background
  ctx.fillRect(0, 0, ctx.canvas.width, ctx.canvas.height)

  const k = dpr * transform.scale
  ctx.setTransform(k, 0, 0, k, dpr * transform.offsetX, dpr * transform.offsetY)
  const onePixel = 1 / k // line widths are given in venue units; this is one device pixel

  // Sections: a faint outline. Their names are not drawn: the label point sits among the seats and
  // is meant for the zoomed-out view (semantic zoom, M5); the picker and the list name every seat.
  ctx.lineWidth = onePixel
  ctx.strokeStyle = palette.muted
  ctx.globalAlpha = 0.4
  for (const section of venue.sections) {
    ctx.beginPath()
    section.outline.forEach(([x = 0, y = 0], i) => (i === 0 ? ctx.moveTo(x, y) : ctx.lineTo(x, y)))
    ctx.closePath()
    ctx.stroke()
  }

  // Stage: which way the venue faces.
  const { stage } = venue
  ctx.fillStyle = palette.muted
  ctx.globalAlpha = 0.25
  ctx.fillRect(stage.x, stage.y, stage.width, stage.height)
  ctx.globalAlpha = 1
  ctx.font = `${11 / transform.scale}px ui-monospace, monospace` // 11 CSS px whatever the scale
  ctx.textAlign = 'center'
  ctx.textBaseline = 'middle'
  ctx.fillText('STAGE', stage.x + stage.width / 2, stage.y + stage.height / 2)

  // Seats, one path per state (three fills instead of 2,000). Taken seats are graphite at low
  // opacity, which stands apart from free seats in both the dark and the light theme; signal
  // (PRD 7: "yours" only) marks the seats you picked or hold.
  const styles: Record<SeatState, { colour: string; alpha: number }> = {
    taken: { colour: palette.muted, alpha: 0.35 },
    available: { colour: palette.foreground, alpha: 1 },
    mine: { colour: palette.signal, alpha: 1 },
  }
  for (const state of ['taken', 'available', 'mine'] as const) {
    ctx.beginPath()
    seats.forEach((seat, i) => {
      if (stateOf(i) === state) {
        ctx.moveTo(seat.x + venue.seatRadius, seat.y)
        ctx.arc(seat.x, seat.y, venue.seatRadius, 0, Math.PI * 2)
      }
    })
    ctx.fillStyle = styles[state].colour
    ctx.globalAlpha = styles[state].alpha
    ctx.fill()
  }
  ctx.globalAlpha = 1
}

/** The CSS colour tokens (src/styles/tokens.css), read at draw time: a canvas can't use var(). */
export function readPalette(element: Element): Palette {
  const style = getComputedStyle(element)
  const token = (name: string, fallback: string) => style.getPropertyValue(name).trim() || fallback
  return {
    background: token('--bg', '#0b0b0c'),
    foreground: token('--fg', '#f2f0eb'),
    muted: token('--graphite', '#8a8a8e'),
    signal: token('--signal', '#ff3b1f'),
  }
}
