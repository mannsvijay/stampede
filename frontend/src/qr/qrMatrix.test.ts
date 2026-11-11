import qrcode from 'qrcode-generator'
import { describe, expect, it } from 'vitest'

import { qrModules } from './qrMatrix'

// The test vector of docs/ticket-format.md section 5: a real 195-character ticket string.
const TICKET =
  'ARbqCjcRERERERFBEYERERERERERIiIiIiIiQiKCIiIiIiIiIjMzMzMzM0MzgzMzMzMzMzNERERERERERIREREREREREAAAAAGrzB0AAAAAB' +
  '.5_GwMTIhLuHjOFTr-jOb1j7rXMTRC7Q9vvQm19L6dn_O5UJ4yvrtJ5tus0JFTitX5_BC0Ty6sfy5YeMWACjuCg'

function sizeWith(level: 'L' | 'M' | 'Q' | 'H'): number {
  const qr = qrcode(0, level)
  qr.addData(TICKET, 'Byte')
  qr.make()
  return qr.getModuleCount()
}

describe('qrModules', () => {
  it('a real 195-character ticket is a 57 × 57 grid (version 10)', () => {
    expect(TICKET).toHaveLength(195)

    const grid = qrModules(TICKET)

    expect(grid).toHaveLength(57)
    grid.forEach((row) => expect(row).toHaveLength(57))
  })

  it('57 means level M in byte mode: the other levels give other sizes for the same string', () => {
    // Byte capacity: version 9 holds 230 bytes at L (53 × 53); version 10 holds 213 at M; Q and H
    // need larger versions. So the 57 above can only come from level M.
    expect(sizeWith('L')).toBe(53)
    expect(sizeWith('M')).toBe(57)
    expect(sizeWith('Q')).toBeGreaterThan(57)
    expect(sizeWith('H')).toBeGreaterThan(57)
  })

  it('has the three finder patterns (7 × 7 squares with a dark border) in the corners', () => {
    const grid = qrModules(TICKET)
    const n = grid.length
    for (const [top, left] of [
      [0, 0],
      [0, n - 7],
      [n - 7, 0],
    ] as const) {
      for (let i = 0; i < 7; i++) {
        expect(grid[top]?.[left + i]).toBe(true) // top edge
        expect(grid[top + 6]?.[left + i]).toBe(true) // bottom edge
        expect(grid[top + i]?.[left]).toBe(true) // left edge
        expect(grid[top + i]?.[left + 6]).toBe(true) // right edge
      }
      expect(grid[top + 1]?.[left + 1]).toBe(false) // the light ring
      expect(grid[top + 3]?.[left + 3]).toBe(true) // the dark centre
    }
  })

  it('is deterministic: the same ticket always draws the same code', () => {
    expect(qrModules(TICKET)).toEqual(qrModules(TICKET))
  })
})
