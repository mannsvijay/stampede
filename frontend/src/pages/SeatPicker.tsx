import { useMemo, useState } from 'react'

import type { LayoutSeat } from '../api/client'
import styles from './SeatsPage.module.css'

interface SeatPickerProps {
  seats: readonly LayoutSeat[]
  sectionOrder: readonly { code: string; name: string }[]
  /** True if the seat at this index can be added now (free and not already picked). */
  isPickable: (seatIndex: number) => boolean
  priceText: (seat: LayoutSeat) => string
  onAdd: (seatIndex: number) => void
  disabled: boolean
}

interface Row {
  key: string
  label: string
  /** The row's seats by number, with their index in the layout's seat list. */
  seats: { index: number; seat: LayoutSeat }[]
}

/**
 * Picking seats without the canvas: choose a row, then one of its free seats, then "Add seat".
 * Native selects work with the keyboard and screen readers out of the box.
 */
export function SeatPicker({ seats, sectionOrder, isPickable, priceText, onAdd, disabled }: SeatPickerProps) {
  const groups = useMemo(() => groupRows(seats, sectionOrder), [seats, sectionOrder])
  const [rowKey, setRowKey] = useState('')
  const [seatValue, setSeatValue] = useState('')

  const row = groups.flatMap((group) => group.rows).find((r) => r.key === rowKey)
  const choices = row ? row.seats.filter(({ index }) => isPickable(index)) : []
  // A seat chosen earlier may have been taken since (seat map refetched): then nothing is chosen.
  const chosen = choices.find(({ index }) => String(index) === seatValue)?.index ?? null

  function chooseRow(key: string) {
    setRowKey(key)
    setSeatValue('')
  }

  function add() {
    if (chosen !== null) {
      onAdd(chosen)
      setSeatValue('')
    }
  }

  return (
    <fieldset className={styles.picker} disabled={disabled}>
      <legend className="label">Pick with the keyboard</legend>
      <label htmlFor="picker-row">Row</label>
      <select id="picker-row" value={rowKey} onChange={(e) => chooseRow(e.target.value)}>
        <option value="">Choose a row</option>
        {groups.map((group) => (
          <optgroup key={group.name} label={group.name}>
            {group.rows.map((r) => (
              <option key={r.key} value={r.key}>
                {r.label}
              </option>
            ))}
          </optgroup>
        ))}
      </select>
      <label htmlFor="picker-seat">Seat</label>
      <select id="picker-seat" value={chosen ?? ''} onChange={(e) => setSeatValue(e.target.value)} disabled={!row}>
        <option value="">{row && choices.length === 0 ? 'No free seats in this row' : 'Choose a seat'}</option>
        {choices.map(({ index, seat }) => (
          <option key={seat.id} value={index}>
            {`Seat ${seat.number} · ${priceText(seat)}`}
          </option>
        ))}
      </select>
      <button type="button" onClick={add} disabled={chosen === null}>
        Add seat
      </button>
    </fieldset>
  )
}

/**
 * Rows grouped by section, in the venue's section order; rows sorted by label ("A" < "B", "2" < "10")
 * and seats by number. The layout itself lists seats by id, which is no order a person would use.
 */
function groupRows(seats: readonly LayoutSeat[], sectionOrder: readonly { code: string; name: string }[]) {
  type RowSeats = Row['seats']
  const rowsBySection = new Map<string, Map<string, RowSeats>>()
  seats.forEach((seat, index) => {
    let rows = rowsBySection.get(seat.section)
    if (!rows) {
      rows = new Map()
      rowsBySection.set(seat.section, rows)
    }
    const rowSeats = rows.get(seat.row) ?? []
    rowSeats.push({ index, seat })
    rows.set(seat.row, rowSeats)
  })
  const byLabel = (a: [string, RowSeats], b: [string, RowSeats]) => a[0].localeCompare(b[0], 'en', { numeric: true })
  const groups: { name: string; rows: Row[] }[] = []
  for (const section of sectionOrder) {
    const rows = rowsBySection.get(section.code)
    if (!rows) {
      continue
    }
    groups.push({
      name: section.name,
      rows: [...rows.entries()].sort(byLabel).map(([label, rowSeats]) => ({
        key: `${section.code}/${label}`,
        label: `${section.name} · Row ${label}`,
        seats: rowSeats.sort((a, b) => a.seat.number - b.seat.number),
      })),
    })
  }
  return groups
}
