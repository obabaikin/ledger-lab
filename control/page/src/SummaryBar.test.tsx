import { screen, waitFor, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import type { BankView, PeriodView } from './api/client'
import { NO_DATA } from './format'
import { SECTIONS } from './sections'
import { bank, fakeControl, period, series, SERIES, type Answer } from './test/fakeControl'
import { renderAt } from './test/render'
import { en } from './texts/en'

const PERIODS = 'GET /api/bank/periods?limit=10'
const POLLS_UNTIL_CHANGE_MS = 3000

function page(answer: BankView | (() => Answer), periods: PeriodView[] = []) {
  fakeControl({
    'GET /api/bank': typeof answer === 'function' ? answer : { status: 200, body: answer },
    [PERIODS]: { status: 200, body: { periods } },
    [SERIES]: { status: 200, body: series() },
  })
  renderAt('/')
}

const bar = () => screen.findByRole('navigation', { name: en.summary.label })
const dotOf = (element: HTMLElement) => element.querySelector('.dot')!.className

describe('the summary bar', () => {
  it('shows the speed now and the three service levels, each dot colored as its card', async () => {
    const view = bank()
    page({ ...view, posting: { ...view.posting, targets: [{ ...view.posting.targets[0]!, share: 0.9997, budgetUsed: 0.6 }, view.posting.targets[1]!] } })
    await bar()

    expect(screen.getByTestId('sum-now')).toHaveAccessibleName(`${en.summary.now} ${en.summary.speed('400')}`)
    expect(screen.getByTestId('sum-availability')).toHaveAccessibleName(`${en.summary.availability} 99.83 %`)
    expect(screen.getByTestId('sum-posting-0')).toHaveAccessibleName(`${en.summary.within(3)} 99.97 %`)
    expect(screen.getByTestId('sum-posting-1')).toHaveAccessibleName(`${en.summary.within(5)} 99.98 %`)
    for (const [item, card] of [['sum-availability', 'slo-availability-value'], ['sum-posting-0', 'slo-posting-0-value'], ['sum-posting-1', 'slo-posting-1-value']] as const)
      expect(dotOf(screen.getByTestId(item))).toBe(dotOf(screen.getByTestId(card)))
    expect(dotOf(screen.getByTestId('sum-posting-0'))).toBe('dot warn')
  })

  it('shows the pulse in yellow, with the same number as the heading from the same answer', async () => {
    page(bank({ clients: { ...bank().clients!, targetTps: 1400, pulse: { tps: 1400, until: '2026-11-11T10:30:20Z' } } }))
    await bar()

    expect(screen.getByTestId('sum-now')).toHaveAccessibleName(`${en.summary.now} ${en.summary.pulse('1400')}`)
    expect(screen.getByTestId('sum-now')).toHaveClass('warn')
    expect(screen.getByTestId('hero-rate')).toHaveTextContent(`1400 ${en.hero.unit}`)
  })

  it('shows a grey dot and a dash, not 0, for a speed or level not known yet', async () => {
    const view = bank({ clients: null })
    page({ ...view, slo: { ...view.slo, availability: null } })
    await bar()

    expect(screen.getByTestId('sum-now')).toHaveAccessibleName(`${en.summary.now} ${NO_DATA}`)
    expect(dotOf(screen.getByTestId('sum-now'))).toBe('dot none')
    expect(screen.getByTestId('sum-availability')).toHaveAccessibleName(`${en.summary.availability} ${NO_DATA}`)
    expect(dotOf(screen.getByTestId('sum-availability'))).toBe('dot none')
  })

  it('links each section and each number to a section that is on the page', async () => {
    page(bank(), [period()])
    const nav = await bar()

    expect(within(nav).getAllByRole('link').slice(0, SECTIONS.length).map((a) => [a.textContent, a.getAttribute('href')])).toEqual(
      SECTIONS.map((s) => [en.summary.sections[s], `#${s}`]),
    )
    await screen.findByTestId('sum-period')
    for (const a of within(nav).getAllByRole('link')) expect(document.getElementById(a.getAttribute('href')!.slice(1))).not.toBeNull()
  })

  it('is not shown before the bank is read, as its sections are not there yet', async () => {
    page(() => ({ status: 502 }))

    expect(await screen.findByRole('status')).toBeInTheDocument()
    expect(screen.queryByRole('navigation', { name: en.summary.label })).toBeNull()
  })

  it('keeps the last numbers read while the connection is lost', async () => {
    let up = true
    page(() => (up ? { status: 200, body: bank() } : { status: 502 }))
    await bar()
    up = false

    await screen.findByText(en.connection.lost, {}, { timeout: POLLS_UNTIL_CHANGE_MS })
    expect(screen.getByTestId('sum-now')).toHaveAccessibleName(`${en.summary.now} ${en.summary.speed('400')}`)
    expect(screen.getByTestId('hero-rate')).toHaveTextContent('400')
  })
})

describe('the last period in the summary bar', () => {
  it('is green when the newest finished period balanced, passing over one still being reconciled', async () => {
    page(bank(), [period({ start: '2026-11-11T10:00:00Z', state: 'DRAINING', verdict: null }), period()])

    const item = await screen.findByTestId('sum-period')
    expect(item).toHaveAccessibleName(en.summary.lastBalanced)
    expect(item).toHaveClass('ok')
    expect(item).toHaveTextContent('✓')
  })

  it('is red with the outcome when the newest finished period failed or did not balance', async () => {
    page(bank(), [period({ state: 'FAILED', verdict: null, failure: 'DRAIN_TIMED_OUT' })])

    const item = await screen.findByTestId('sum-period')
    expect(item).toHaveAccessibleName(en.summary.lastPeriod(en.periods.state.FAILED))
    expect(item).toHaveClass('bad')
    expect(item).toHaveTextContent('✗')
  })

  it('is red with the verdict for a mismatch', async () => {
    page(bank(), [period({ verdict: 'MISMATCH' })])

    expect(await screen.findByTestId('sum-period')).toHaveAccessibleName(en.summary.lastPeriod(en.periods.verdict.MISMATCH))
  })

  it('is not shown before a period finished', async () => {
    page(bank(), [period({ state: 'DRAINING', verdict: null })])
    await bar()
    await waitFor(() => expect(screen.getByTestId('current-period')).toBeInTheDocument())

    expect(screen.queryByTestId('sum-period')).toBeNull()
  })
})

describe('the sections', () => {
  it('each have their heading and the question they answer', async () => {
    page(bank())
    await bar()

    const sections: [string, string, string][] = [
      ['live', en.bank.heading, en.bank.question],
      ['charts', en.charts.heading, en.charts.question],
      ['slo', en.availability.heading, en.availability.question],
      ['periods', en.periods.heading, en.periods.question],
    ]
    for (const [id, heading, question] of sections) {
      const section = document.getElementById(id)!
      expect(within(section).getByRole('heading', { level: 2 })).toHaveTextContent(heading)
      expect(section.querySelector('.question')).toHaveTextContent(question)
    }
  })

  it('name the columns of the periods table', async () => {
    page(bank(), [period()])
    const periods = await screen.findByRole('table')

    expect(within(periods).getAllByRole('columnheader').map((h) => h.textContent)).toEqual([
      en.periods.columns.period, en.periods.columns.transfers, en.periods.columns.verdict,
    ])
  })

  it('name service levels SLOs, and the word target is not on the page', async () => {
    page(bank())
    await bar()

    expect(screen.getByRole('heading', { level: 2, name: en.availability.heading })).toHaveTextContent('Service levels · SLO')
    expect(screen.getByTestId('slo-availability')).toHaveTextContent('SLO ≥ 99.9 %')
    expect(document.body.textContent).not.toMatch(/target/i)
    expect(screen.getByText(en.availability.ruleSummary).closest('details')).toHaveTextContent(en.availability.sliIntro(30))
  })
})

describe('the heading', () => {
  it('says the bank takes the speed now, with the number four digits wide', async () => {
    page(bank())

    const speed = await screen.findByTestId('hero-rate')
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent(`${en.hero.before} 400 ${en.hero.unit} ${en.hero.after}`)
    expect(speed).toHaveClass('ok')
    expect(speed.querySelector('.rate')).toHaveTextContent('400')
  })

  it('asks the question without a number until the clients answered', async () => {
    page(bank({ clients: null }))
    await bar()

    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent(en.inviteForm.heading)
    expect(screen.queryByTestId('hero-rate')).toBeNull()
  })
})
