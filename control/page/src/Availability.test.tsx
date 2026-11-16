import { render, screen, within } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { Availability, health } from './Availability'
import { dayAndMonth, utcDayAndMonth } from './format'
import { bank } from './test/fakeControl'
import { en } from './texts/en'

const NOW = Date.parse('2026-11-30T15:00:00Z')
const day = (daysBack: number) => new Date(Date.parse('2026-11-30T00:00:00Z') - daysBack * 86_400_000).toISOString().slice(0, 10)

describe('service levels', () => {
  it('color a level green below half of the budget, yellow from half to 90 %, red above it or with the target missed', () => {
    expect(health(0.49, false)).toBe('ok')
    expect(health(0.5, false)).toBe('warn')
    expect(health(0.9, false)).toBe('warn')
    expect(health(0.91, false)).toBe('bad')
    expect(health(0.1, true)).toBe('bad')
  })

  it('say the state in words next to the value', () => {
    const view = bank()
    render(
      <Availability
        bank={{
          ...view,
          slo: { ...view.slo, availability: 0.9995, badMinutes: 25 },
          posting: { ...view.posting, targets: [{ ...view.posting.targets[0]!, share: 0.94, budgetUsed: 0.1 }, view.posting.targets[1]!] },
        }}
      />,
    )

    expect(within(screen.getByTestId('slo-posting-0-value')).getByRole('img')).toHaveAccessibleName(en.availability.health.bad)
    expect(within(screen.getByTestId('slo-posting-1-value')).getByRole('img')).toHaveAccessibleName(en.availability.health.ok)
    expect(within(screen.getByTestId('slo-availability-value')).getByRole('img')).toHaveAccessibleName(en.availability.health.warn)
  })

  it('draw a bar for every day with a record and an empty slot for a day without, with the even pace at the budget over 30 days', () => {
    vi.spyOn(Date, 'now').mockReturnValue(NOW)
    const days = Array.from({ length: 30 }, (_, i) => ({ day: day(29 - i), count: i === 29 ? 3 : 1 }))
    const view = bank()
    render(<Availability bank={{ ...view, slo: { ...view.slo, days } }} />)

    const card = within(screen.getByTestId('slo-availability'))
    const bars = card.getAllByTestId('day-bar')
    expect(bars).toHaveLength(30)
    expect(bars.at(-1)).toHaveAccessibleName(en.availability.badMinutesOn(utcDayAndMonth(day(0)), '3'))
    expect(bars.at(-1)!.getAttribute('height')).toBe('40')
    expect(card.getByTestId('even-pace').getAttribute('y1')).toBe(String(40 - (43 / 30 / 3) * 40))
    expect(card.getByText(en.availability.daysAgo(30))).toBeInTheDocument()
  })

  it('leave a slot empty for a day without a record, bars the others in their place', () => {
    vi.spyOn(Date, 'now').mockReturnValue(NOW)
    const view = bank()
    const target = view.posting.targets[0]!
    render(<Availability bank={{ ...view, posting: { ...view.posting, targets: [{ ...target, days: [{ day: day(10), count: 50 }, { day: day(0), count: 0 }] }, view.posting.targets[1]!] } }} />)

    const bars = within(screen.getByTestId('slo-posting-0')).getAllByTestId('day-bar')
    expect(bars).toHaveLength(2)
    expect(bars.map((b) => b.getAttribute('x'))).toEqual([String(19 * 12), String(29 * 12)])
    expect(bars[0]).toHaveAccessibleName(en.availability.slowTransfersOn(utcDayAndMonth(day(10)), '50'))
  })

  it('say not measured before the first judged transfer, without a budget, and since when transfers are counted while the window is short', () => {
    const view = bank()
    const posting = {
      ...view.posting,
      since: '2026-11-17T03:00:00Z',
      judged: 0,
      targets: view.posting.targets.map((target) => ({ ...target, within: 0, share: null, budgetUsed: null, days: [] })),
    }
    render(<Availability bank={{ ...view, posting }} />)

    expect(screen.getByTestId('slo-posting-0-value')).toHaveTextContent(en.availability.notMeasured)
    expect(screen.queryByTestId('slo-posting-0-used')).toBeNull()
    expect(screen.getByTestId('posting-since')).toHaveTextContent(en.availability.postingSince(dayAndMonth('2026-11-17T03:00:00Z')))
  })

  it('name a bar by its UTC day for a visitor west of UTC too', () => {
    vi.stubEnv('TZ', 'America/New_York')
    try {
      vi.spyOn(Date, 'now').mockReturnValue(NOW)
      const view = bank()
      render(<Availability bank={{ ...view, slo: { ...view.slo, days: [{ day: '2026-11-30', count: 2 }] } }} />)

      expect(within(screen.getByTestId('slo-availability')).getByTestId('day-bar')).toHaveAccessibleName(en.availability.badMinutesOn('30 Nov', '2'))
    } finally {
      vi.unstubAllEnvs()
    }
  })

  it('fill the budget bar to its share and cap it at the whole bar when the target is missed', () => {
    const view = bank()
    render(<Availability bank={{ ...view, posting: { ...view.posting, targets: [{ ...view.posting.targets[0]!, share: 0.94, budgetUsed: 1.2 }, view.posting.targets[1]!] } }} />)

    expect(screen.getByTestId('slo-posting-0-used')).toHaveTextContent('120 %')
    expect(within(screen.getByTestId('slo-posting-0')).getByRole('meter')).toHaveAttribute('aria-valuenow', '1')
  })
})
