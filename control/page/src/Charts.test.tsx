import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { SeriesView } from './api/client'
import { Charts, logY, nearestPoint } from './Charts'
import { hoursMinutes } from './format'
import { bank, fakeControl, series, SERIES, type Answer } from './test/fakeControl'
import { renderAt } from './test/render'
import { en } from './texts/en'

const SHORT_SERIES = 'GET /api/bank/series?range=5m'
/** The svg's width on screen equals its viewBox's, so a client x is a place in the drawing. */
const DRAWN = { left: 0, top: 0, width: 420, height: 170, right: 420, bottom: 170, x: 0, y: 0, toJSON: () => ({}) }
/** The middle of the plotted width: between the left axis (44) and the right margin (8). */
const MIDDLE_X = 44 + (420 - 44 - 8) / 2

const allSeries = (): SeriesView =>
  series({
    series: [
      { name: 'ACCEPTED_PER_SECOND', values: [400, 410, null] },
      { name: 'POSTED_PER_SECOND', values: [398, 409, null] },
      { name: 'REJECTED_PER_SECOND', values: [2, 3, null] },
      { name: 'BUSY_PER_SECOND', values: [0, 7, null] },
      { name: 'BANK_LATENCY_P50_MS', values: [120, 0, 130] },
      { name: 'BANK_LATENCY_P99_MS', values: [480, 1500, 520] },
    ],
  })

function deferred() {
  let resolve: (answer: Answer) => void = () => undefined
  const promise = new Promise<Answer>((r) => (resolve = r))
  return { promise, resolve }
}

afterEach(() => vi.restoreAllMocks())

describe('the range of the charts', () => {
  it('asks for 5 minutes once picked, and draws no answer for 2 hours that comes after the pick', async () => {
    const long = deferred()
    const short = deferred()
    const calls = fakeControl({
      'GET /api/bank': { status: 200, body: bank() },
      'GET /api/bank/periods?limit=10': { status: 200, body: { periods: [] } },
      [SERIES]: () => long.promise,
      [SHORT_SERIES]: () => short.promise,
    })
    renderAt('/')
    const picker = await screen.findByRole('combobox', { name: en.charts.rangeLabel })
    expect(picker).toHaveValue('2h')

    fireEvent.change(picker, { target: { value: '5m' } })
    long.resolve({ status: 200, body: series({ series: [] }) })

    await waitFor(() => expect(calls.some((c) => c.path === '/api/bank/series?range=5m')).toBe(true))
    expect(screen.queryByText(en.charts.noData)).toBeNull()
    expect(screen.queryByRole('img', { name: en.charts.flow })).toBeNull()

    short.resolve({ status: 200, body: series({ stepSeconds: 5 }) })
    expect(await screen.findByRole('img', { name: en.charts.flow })).toBeInTheDocument()
    expect(screen.getByRole('combobox', { name: en.charts.rangeLabel })).toHaveValue('5m')
  })

  it('offers 2 min, 5 min, 30 min and 2 h in the words of the page texts', () => {
    render(<Charts view={allSeries()} chaos={[]} range="2h" onRange={() => undefined} />)

    expect(within(screen.getByRole('combobox')).getAllByRole('option').map((o) => o.textContent)).toEqual([
      en.charts.ranges['2m'],
      en.charts.ranges['5m'],
      en.charts.ranges['30m'],
      en.charts.ranges['2h'],
    ])
  })
})

describe('the charts', () => {
  it('show the values of every line at the grid point under the pointer, and a dash where monitoring has none', () => {
    vi.spyOn(Element.prototype, 'getBoundingClientRect').mockReturnValue(DRAWN)
    const view = allSeries()
    render(<Charts view={view} chaos={[]} range="2h" onRange={() => undefined} />)

    fireEvent.mouseMove(screen.getByRole('img', { name: en.charts.flow }), { clientX: MIDDLE_X })

    const at = hoursMinutes(new Date(Date.parse(view.from) + view.stepSeconds * 1000).toISOString())
    const readouts = screen.getAllByTestId('chart-readout').map((r) => r.textContent)
    expect(readouts).toContain(`${at} · ${en.charts.series.ACCEPTED_PER_SECOND} 410 · ${en.charts.series.POSTED_PER_SECOND} 409`)
    expect(readouts).toContain(`${at} · ${en.charts.series.BANK_LATENCY_P50_MS} 0 ms · ${en.charts.series.BANK_LATENCY_P99_MS} 1.5 s`)
    expect(screen.getAllByTestId('chart-cursor')).toHaveLength(3)

    fireEvent.mouseMove(screen.getByRole('img', { name: en.charts.flow }), { clientX: 420 })
    expect(screen.getAllByTestId('chart-readout').map((r) => r.textContent)).toContain(
      `${hoursMinutes(new Date(Date.parse(view.from) + 2 * view.stepSeconds * 1000).toISOString())} · ${en.charts.series.ACCEPTED_PER_SECOND} — · ${en.charts.series.POSTED_PER_SECOND} —`,
    )
  })

  it('show the newest values before the pointer comes and after it leaves, so the readout never comes and goes', () => {
    vi.spyOn(Element.prototype, 'getBoundingClientRect').mockReturnValue(DRAWN)
    const view = allSeries()
    render(<Charts view={view} chaos={[]} range="2h" onRange={() => undefined} />)
    const chart = screen.getByRole('img', { name: en.charts.flow })
    const newest = `${hoursMinutes(new Date(Date.parse(view.from) + view.stepSeconds * 1000).toISOString())} · ${en.charts.series.ACCEPTED_PER_SECOND} 410 · ${en.charts.series.POSTED_PER_SECOND} 409`

    expect(screen.getAllByTestId('chart-readout').map((r) => r.textContent)).toContain(newest)
    expect(screen.queryByTestId('chart-cursor')).not.toBeInTheDocument()

    fireEvent.mouseMove(chart, { clientX: 420 })
    fireEvent.mouseLeave(chart)

    expect(screen.getAllByTestId('chart-readout').map((r) => r.textContent)).toContain(newest)
    expect(screen.queryByTestId('chart-cursor')).not.toBeInTheDocument()
  })

  it('take the nearest grid point, and keep to the grid at its edges', () => {
    expect(nearestPoint(0.49, 3)).toBe(1)
    expect(nearestPoint(-0.2, 3)).toBe(0)
    expect(nearestPoint(1.3, 3)).toBe(2)
  })

  it('draw the refusals by the rules and as busy on a small chart of their own, apart from the flow', () => {
    render(<Charts view={allSeries()} chaos={[]} range="2h" onRange={() => undefined} />)

    const refused = screen.getByRole('img', { name: en.charts.refused })
    const flow = screen.getByRole('img', { name: en.charts.flow })
    expect(within(refused).getByTestId('line-BUSY_PER_SECOND')).toBeInTheDocument()
    expect(within(refused).getByTestId('line-REJECTED_PER_SECOND')).toBeInTheDocument()
    expect(within(flow).queryByTestId('line-BUSY_PER_SECOND')).toBeNull()
    expect(within(flow).getByTestId('line-ACCEPTED_PER_SECOND')).toBeInTheDocument()
  })

  it('draw the latency on a log scale from 10 ms to 10 s, with dashed guides at 1000 and 5000 ms, and break a line at 0', () => {
    render(<Charts view={allSeries()} chaos={[]} range="2h" onRange={() => undefined} />)

    const latency = within(screen.getByRole('img', { name: en.charts.latency }))
    for (const tick of [en.charts.milliseconds('10'), en.charts.milliseconds('100'), en.charts.seconds('1'), en.charts.seconds('10')]) {
      expect(latency.getByText(tick)).toBeInTheDocument()
    }
    expect(latency.getAllByTestId('latency-guide').map((g) => g.textContent)).toEqual([
      en.charts.guides.BANK_LATENCY_P50_MS(1000),
      en.charts.guides.BANK_LATENCY_P99_MS(5000),
    ])
    expect(latency.queryByTestId('line-BANK_LATENCY_P50_MS')).toBeNull()
    expect(latency.getByTestId('line-BANK_LATENCY_P99_MS')).toBeInTheDocument()
  })

  it('space 10 ms, 100 ms and 1 s evenly, and give 0 no place', () => {
    expect(logY(10, 170)! - logY(100, 170)!).toBeCloseTo(logY(100, 170)! - logY(1000, 170)!)
    expect(logY(10, 170)!).toBeGreaterThan(logY(100, 170)!)
    expect(logY(0, 170)).toBeNull()
  })
})
