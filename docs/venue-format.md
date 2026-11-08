# Venue file format (version 1)

A venue file describes one physical venue: its sections, rows and seats, with coordinates a Canvas2D
seat map can draw directly. The seed loader (`com.stampede.inventory.seed`) reads it into the
`venues`, `sections` and `seats` tables.

- Demo venue: `backend/src/main/resources/seed/venues/demo-arena.json` (generated, committed).
- Regenerate it: `cd backend && ./gradlew generateVenue`. Don't edit it by hand; a test fails if the
  file and the generator drift apart.

## Example

```json
{
  "formatVersion": 1,
  "slug": "demo-arena",
  "name": "Stampede Demo Arena",
  "width": 1200,
  "height": 700,
  "seatRadius": 4,
  "stage": { "x": 450.0, "y": 30.0, "width": 300.0, "height": 50.0 },
  "sections": [
    {
      "code": "FL-L",
      "name": "Floor Left",
      "defaultTier": "PLATINUM",
      "label": { "x": 485.0, "y": 184.0 },
      "outline": [[384.0, 124.0], [586.0, 124.0], [586.0, 244.0], [384.0, 244.0]],
      "rows": [
        {
          "label": "A",
          "seats": [
            { "number": 1, "x": 390.0, "y": 130.0, "aisle": true },
            { "number": 2, "x": 400.0, "y": 130.0 }
          ]
        }
      ]
    }
  ]
}
```

## Coordinates

- Units are abstract **venue units**. The origin is the top-left corner, x grows to the right and
  **y grows downwards**, the same as Canvas2D. Every point lies inside `0..width` x `0..height`.
- A seat's `x, y` is the **centre** of the seat; draw it as a circle of radius `seatRadius`.
- To fit the venue on screen, scale `width x height` to the canvas; no other transform is needed.
- `stage` is a rectangle (top-left corner plus size) that shows which way the venue faces.
- The generator rounds every coordinate to 0.1.

## Fields

| Field | Required | Meaning |
|---|---|---|
| `formatVersion` | yes | Must be `1`. |
| `slug` | yes | Stable identifier, `[A-Za-z0-9_-]+`. Seed ids are derived from it, so never rename it. |
| `name` | yes | Display name (`venues.name`). |
| `width`, `height` | yes | Size of the drawing area, in venue units. |
| `seatRadius` | yes | Seat circle radius for drawing. |
| `stage` | yes | `{x, y, width, height}`. |
| `sections[].code` | yes | Unique in the venue, `[A-Za-z0-9_-]+` (`sections.code`). |
| `sections[].name` | yes | Display name. |
| `sections[].defaultTier` | yes | A tier **name**. Must match one of the event's tiers when an event is seeded (`sections.default_tier`). |
| `sections[].label` | yes | `{x, y}` where the section's name is drawn when zoomed out. |
| `sections[].outline` | yes | Polygon of at least 3 `[x, y]` points, drawn as a block when zoomed out (semantic zoom, PRD 8.4). |
| `rows[].label` | yes | Unique in the section, `[A-Za-z0-9_-]+`, front row first (`seats.row_label`). |
| `seats[].number` | yes | Positive, unique within the row (`seats.number`). |
| `seats[].x`, `seats[].y` | yes | Seat centre. |
| `seats[].aisle` | no | Default `false`. |
| `seats[].accessible` | no | Default `false` (wheelchair position). |

Unknown fields are rejected, so a typo fails loudly instead of being ignored.

## What goes where in the database

| File | Database |
|---|---|
| venue `name` | `venues.name` |
| everything except `rows` (format version, slug, name, size, seat radius, stage, and each section's code, name, label and outline) | `venues.layout_json` |
| section `code`, `defaultTier` | `sections` |
| each seat | one `seats` row; `aisle`/`accessible` become `attrs` = `{"aisle": bool, "accessible": bool}`, both keys always present |

Seats live only in the `seats` table, not in `layout_json`, so there is one source of truth for them.
`layout_json` is what a renderer needs to draw the venue zoomed out (and the silhouette on the
departures board) without loading every seat.

## Seed ids

Every seeded row gets a name-based UUID (version 3) built from the slugs and codes, for example
`stampede-seed:seat:demo-arena:FL-L:A:1`. The same file gives the same ids on every machine. See
`SeedIds` and decision D-006.

## Loading rules

- Both files (venue and event) are validated before anything is written; all problems are reported at once.
- Loading is idempotent: running it again with the same files inserts nothing.
- If a file changed after it was loaded (a seat moved, a section's tier changed, a price changed), the
  loader fails and rolls back. Loaded data is never modified, because event seats (and later holds,
  orders and tickets) point at it. To reset local data: `docker compose down -v`, `docker compose up -d`,
  then run the seed profile again.

## Event file

The demo event lives in `backend/src/main/resources/seed/events/demo-drop.json`:

| Field | Meaning |
|---|---|
| `slug` | Stable identifier for the event's seed ids. |
| `venueSlug` | Must equal the venue file's `slug`. |
| `title`, `status`, `strategy`, `perUserLimit` | Copied to `events` (values checked by the database CHECK constraints). |
| `dropInHours`, `startsInDays` | `drop_at` / `starts_at` relative to the **first** load; they don't move afterwards. |
| `tiers[]` | `{name, pricePaise}`; names must be unique and cover every section's `defaultTier`. |

Each seat of the venue gets one `event_seats` row, `AVAILABLE`, priced by its section's default tier.
