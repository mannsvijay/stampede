import { useMemo } from 'react'

import { qrModules } from './qrMatrix'

/** Light modules around the code that scanners need to find its edges (the QR spec's minimum). */
const QUIET_ZONE = 4

/**
 * A ticket's QR code as SVG: one path of 1 × 1 squares in a viewBox of whole modules, so it is sharp
 * at any size. Always dark on white, whatever the page's theme: scanners expect that contrast.
 */
export function TicketQr({ value, label }: { value: string; label: string }) {
  const modules = useMemo(() => qrModules(value), [value])
  const size = modules.length + 2 * QUIET_ZONE
  const path = useMemo(() => {
    const parts: string[] = []
    modules.forEach((row, y) =>
      row.forEach((dark, x) => {
        if (dark) {
          parts.push(`M${x + QUIET_ZONE} ${y + QUIET_ZONE}h1v1h-1z`)
        }
      }),
    )
    return parts.join('')
  }, [modules])

  return (
    <svg
      role="img"
      aria-label={label}
      viewBox={`0 0 ${size} ${size}`}
      width={size * 3}
      height={size * 3}
      shapeRendering="crispEdges"
      data-modules={modules.length}
    >
      <rect width={size} height={size} fill="#fff" />
      <path d={path} fill="#000" />
    </svg>
  )
}
