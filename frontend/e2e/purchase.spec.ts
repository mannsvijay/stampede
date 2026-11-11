import { expect, test } from '@playwright/test'

import type { EventLayout, EventList, SeatMap } from '../src/api/client'
import { decodeAvailability } from '../src/seatmap/availability'
import { CANVAS_PADDING, fitTransform } from '../src/seatmap/geometry'
import { seatName, sectionNames } from '../src/seatmap/seatNames'

/**
 * The whole purchase in a real browser against the real backend: register, pick a seat by clicking
 * the canvas, hold it, pay, and see the signed ticket's QR code in the wallet.
 *
 * Needs the compose stack with `seed,demo-open-sale` (the demo drop on sale now). Every run
 * registers a new user and buys one seat, which stays sold (2,014 seats: reset the data with
 * `docker compose down -v` long before they run out).
 */
test('register, pick a seat on the map, hold, pay, and see the ticket QR in the wallet', async ({ page, request }) => {
  // The demo drop, which must be on sale now.
  const events = (await (await request.get('/events')).json()) as EventList
  const drop = events.items.find((e) => Date.parse(e.dropAt) <= Date.parse(events.serverTime))
  expect(drop, 'no drop on sale: start the stack with STAMPEDE_COMPOSE_PROFILES=seed,demo-open-sale').toBeDefined()
  const eventId = drop!.id

  await test.step('register (which logs in)', async () => {
    await page.goto('/register')
    await page.getByLabel('Email').fill(`e2e-${Date.now()}-${Math.floor(Math.random() * 1e6)}@example.test`)
    await page.getByLabel('Password').fill('correct horse battery staple')
    await page.getByRole('button', { name: 'Create account' }).click()
    await expect(page.getByRole('button', { name: 'Log out' })).toBeVisible()
  })

  await test.step('open the seat map from the board', async () => {
    await page.goto('/')
    await page.getByRole('link', { name: drop!.title }).click()
    await page.getByRole('link', { name: 'Pick seats' }).click()
    await expect(page).toHaveURL(`/drops/${eventId}/seats`)
  })

  // A free seat, from the same public endpoints the page reads; a random one so that runs (and
  // anyone else testing at the same time) rarely collide.
  const layout = (await (await request.get(`/events/${eventId}/layout`)).json()) as EventLayout
  const seatMap = (await (await request.get(`/events/${eventId}/seatmap`)).json()) as SeatMap
  const available = decodeAvailability(seatMap.available, layout.seats.length)
  const free = layout.seats.filter((_, i) => available[i])
  expect(free.length, 'the demo drop is sold out: reset the data').toBeGreaterThan(0)
  const seat = free[Math.floor(Math.random() * free.length)]!
  const name = seatName(seat, sectionNames(layout))

  await test.step(`click ${name} on the canvas`, async () => {
    const canvas = page.locator('canvas')
    await expect(canvas).toBeVisible()
    const box = await canvas.boundingBox()
    expect(box!.width).toBeGreaterThan(0)
    // The canvas's own maths (geometry.ts): venue units to CSS pixels inside the canvas.
    const t = fitTransform(layout.venue.width, layout.venue.height, box!.width, box!.height, CANVAS_PADDING)
    await canvas.click({ position: { x: seat.x * t.scale + t.offsetX, y: seat.y * t.scale + t.offsetY } })
    await expect(page.getByRole('list', { name: 'Selected seats' })).toContainText(name)
  })

  await test.step('hold it', async () => {
    await page.getByRole('button', { name: 'Hold 1 seat' }).click()
    await expect(page.getByTestId('hold-countdown')).toBeVisible()
    await expect(page.getByRole('list', { name: 'Held seats' })).toContainText(name)
  })

  await test.step('check out and pay', async () => {
    await page.getByRole('button', { name: 'Continue to checkout' }).click()
    await expect(page.getByRole('list', { name: 'Receipt' })).toContainText(name)
    await page.getByRole('button', { name: /^Pay / }).click()
    await expect(page).toHaveURL('/wallet')
  })

  await test.step('the ticket and its QR code are in the wallet', async () => {
    const label = `Section ${seat.section} · Row ${seat.row} · Seat ${seat.number}`
    const qr = page.getByRole('img', { name: `QR code for ${label}` })
    await expect(qr).toBeVisible()
    await expect(qr).toHaveAttribute('data-modules', '57') // 195 characters, byte mode, level M
    await expect(page.getByText(label)).toBeVisible()
    await expect(page.getByRole('heading', { name: 'Tickets on the way' })).toHaveCount(0)
  })
})
