import type { EventLayout, LayoutSeat } from '../api/client'

/** "Floor Left, row A, seat 3": what a screen reader and the messages call a seat. */
export function seatName(seat: LayoutSeat, sectionNames: ReadonlyMap<string, string>): string {
  return `${sectionNames.get(seat.section) ?? seat.section}, row ${seat.row}, seat ${seat.number}`
}

/** Section code → display name. */
export function sectionNames(layout: EventLayout): Map<string, string> {
  return new Map(layout.venue.sections.map((section) => [section.code, section.name]))
}

/** "A", "A and B", "A, B and C". */
export function joinNames(names: readonly string[]): string {
  if (names.length <= 1) {
    return names[0] ?? ''
  }
  return `${names.slice(0, -1).join(', ')} and ${names[names.length - 1]}`
}
