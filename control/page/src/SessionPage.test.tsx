import { screen, waitFor, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import type { LiveView, RunView } from './api/client'
import { RUN_NOT_FINISHED } from './Controls'
import { RUNS_LISTED } from './SessionPage'
import { fakeControl, run, RUN_ID, session, SESSION_ID, type Answer } from './test/fakeControl'
import { renderAt } from './test/render'

const SESSION = `/api/sessions/${SESSION_ID}`
const RUNS = `${SESSION}/runs`
const RUN = `${RUNS}/${RUN_ID}`

const live = (changes: Partial<LiveView> = {}): LiveView => ({
  stats: {
    runId: RUN_ID,
    at: '2026-10-12T09:01:05Z',
    bankLatency: { p50Ms: null, p99Ms: null },
    totals: { accepted: 1000, posted: 990, rejected: 5, postedAmountCents: 99_000, commandsSeen: 1000, inFlight: 5, rejectedByReason: {} },
    series: [],
  },
  clients: { requested: 1100, acknowledged: 990, failed: 0, unknown: 0, retries: 0, duplicatesSent: 0, requestedPerSecond: [] },
  pods: null,
  ledgerDuplicatesAtLeast: 0,
  readAt: '2026-10-12T09:01:05Z',
  chaos: [],
  ...changes,
})

function control(runs: RunView[], extra: Record<string, Answer | (() => Answer)> = {}) {
  return fakeControl({
    [`GET ${SESSION}`]: { status: 200, body: session() },
    [`GET ${RUNS}`]: { status: 200, body: { items: runs.map((r) => ({ ...r, live: null })) } },
    ...Object.fromEntries(runs.map((r) => [`GET ${RUNS}/${r.runId}`, { status: 200, body: r }])),
    ...extra,
  })
}

describe('session page', () => {
  it('without runs shows only the form', async () => {
    control([])
    renderAt(`/s/${SESSION_ID}`)

    expect(await screen.findByRole('button', { name: /Start the run/ })).toBeEnabled()
    expect(screen.queryByLabelText('Phases')).not.toBeInTheDocument()
    expect(screen.queryByRole('img', { name: /Transfers flowing/ })).not.toBeInTheDocument()
  })

  it('shows a run as soon as Start is pressed, without a reload', async () => {
    const queued = run({ state: 'QUEUED', position: 1 })
    let started = false
    fakeControl({
      [`GET ${SESSION}`]: { status: 200, body: session() },
      [`GET ${RUNS}`]: () => ({ status: 200, body: { items: started ? [queued] : [] } }),
      [`POST ${RUNS}`]: () => {
        started = true
        return { status: 202, body: queued }
      },
      [`GET ${RUN}`]: { status: 200, body: queued },
    })
    renderAt(`/s/${SESSION_ID}`)

    ;(await screen.findByRole('button', { name: /Start the run/ })).click()

    expect(await screen.findByTestId('queued', {}, { timeout: 3000 })).toHaveTextContent('Queued · #1 in line')
  })

  it('names an unknown session', async () => {
    fakeControl({ [`GET ${SESSION}`]: { status: 404, body: { error: 'session not found' } } })
    renderAt(`/s/${SESSION_ID}`)

    expect(await screen.findByRole('alert')).toHaveTextContent('Unknown session')
  })

  it('does not call the session unknown when only a run of it is not found', async () => {
    const running = run({ state: 'RUNNING', startedAt: '2026-10-12T09:01:00Z' })
    control([running], { [`GET ${RUN}`]: { status: 404, body: { error: 'run not found' } } })
    renderAt(`/s/${SESSION_ID}`)

    await screen.findByLabelText('Phases')
    await new Promise((resolve) => setTimeout(resolve, 50))
    expect(screen.queryByText(/Unknown session/)).not.toBeInTheDocument()
  })

  it('holds Start back, with the reason on the button, while a run of the session is not finished', async () => {
    control([run({ state: 'RUNNING', startedAt: '2026-10-12T09:01:00Z', live: live() })])
    renderAt(`/s/${SESSION_ID}`)

    const startButton = await screen.findByRole('button', { name: /Start the run/ })
    await waitFor(() => expect(startButton).toBeDisabled())
    expect(startButton).toHaveTextContent(RUN_NOT_FINISHED)
  })

  it('lets a new run start once the last one is final', async () => {
    control([run({ state: 'EXPIRED', failure: 'queue timeout' })])
    renderAt(`/s/${SESSION_ID}`)

    await screen.findByTestId('expired')
    expect(screen.getByRole('button', { name: /Start the run/ })).toBeEnabled()
  })

  it('shows a queued run as its place in line instead of the diagram', async () => {
    control([run({ state: 'QUEUED', position: 3 })])
    renderAt(`/s/${SESSION_ID}`)

    expect(await screen.findByTestId('queued')).toHaveTextContent('Queued · #3 in line')
    expect(screen.queryByRole('img', { name: /Transfers flowing/ })).not.toBeInTheDocument()
  })

  it('shows an expired run as its state and reason instead of the diagram', async () => {
    control([run({ state: 'EXPIRED', failure: 'waited in the queue longer than 5 min' })])
    renderAt(`/s/${SESSION_ID}`)

    const expired = await screen.findByTestId('expired')
    expect(expired).toHaveTextContent('Expired')
    expect(expired).toHaveTextContent('waited in the queue longer than 5 min')
    expect(screen.queryByRole('img', { name: /Transfers flowing/ })).not.toBeInTheDocument()
  })

  it('marks the books final within Bank finishing', async () => {
    control([run({ state: 'DRAINING', startedAt: '2026-10-12T09:01:00Z', booksFinalAt: '2026-10-12T09:01:40Z', live: live() })])
    renderAt(`/s/${SESSION_ID}`)

    const phases = await screen.findByLabelText('Phases')
    await waitFor(() => expect(within(phases).getByText(/Bank finishing/)).toHaveTextContent('Bank finishing · books final ✓'))
  })

  it('offers Stop only while the clients are sending', async () => {
    control([run({ state: 'DRAINING', startedAt: '2026-10-12T09:01:00Z', live: live() })])
    renderAt(`/s/${SESSION_ID}`)

    await screen.findByLabelText('Phases')
    expect(screen.queryByRole('button', { name: /Stop/ })).not.toBeInTheDocument()
  })

  it('shows a dash for the clients while the generator has not answered, not 0', async () => {
    control([run({ state: 'RUNNING', startedAt: '2026-10-12T09:01:00Z', live: live({ clients: null }) })])
    renderAt(`/s/${SESSION_ID}`)

    await waitFor(() => expect(screen.getByTestId('clients-number')).toHaveTextContent('— sent'))
    await waitFor(() => expect(screen.getByTestId('gateway-number')).toHaveTextContent('1,000 accepted'))
  })

  it('keeps the last numbers and says so when control stops answering', async () => {
    let up = true
    const running = run({ state: 'RUNNING', startedAt: '2026-10-12T09:01:00Z', live: live() })
    const answer = (body: unknown) => () => {
      if (!up) throw new TypeError('Failed to fetch')
      return { status: 200, body }
    }
    fakeControl({
      [`GET ${SESSION}`]: answer(session()),
      [`GET ${RUNS}`]: answer({ items: [{ ...running, live: null }] }),
      [`GET ${RUN}`]: answer(running),
    })
    renderAt(`/s/${SESSION_ID}`)
    await waitFor(() => expect(screen.getByTestId('gateway-number')).toHaveTextContent('1,000 accepted'))

    up = false

    expect(await screen.findByText(/Connection lost, retrying/, {}, { timeout: 3000 })).toBeInTheDocument()
    expect(screen.getByTestId('gateway-number')).toHaveTextContent('1,000 accepted')
  })

  it('lists the last runs and offers the rest', async () => {
    const runs = Array.from({ length: RUNS_LISTED + 2 }, (_, i) => run({ runId: `run-${i}`, state: 'RECONCILED', result: 'BALANCED' }))
    control(runs, { [`GET ${RUNS}/run-0/report`]: { status: 409, body: { error: 'report not ready' } } })
    renderAt(`/s/${SESSION_ID}`)

    const showAll = await screen.findByRole('button', { name: `show all (${RUNS_LISTED + 2})` })
    const listed = () => screen.getAllByRole('button', { name: /BALANCED/ })
    expect(listed()).toHaveLength(RUNS_LISTED)
    showAll.click()
    await waitFor(() => expect(listed()).toHaveLength(RUNS_LISTED + 2))
  })

  it('shows a legend with the words of the instance dots', async () => {
    control([run({ state: 'RUNNING', startedAt: '2026-10-12T09:01:00Z', live: live() })])
    renderAt(`/s/${SESSION_ID}`)

    const legend = await screen.findByLabelText('Instances')
    expect(legend).toHaveTextContent('consuming')
    expect(legend).toHaveTextContent('starting')
    expect(legend).toHaveTextContent('no answer to Prometheus')
    expect(legend).toHaveTextContent('no data')
  })
})

describe('visitor without an invite', () => {
  it('sees the question, the way a transfer takes without numbers, the links and how to get an invite; no form', async () => {
    fakeControl({})
    renderAt('/')

    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('Can a bank lose a server')
    expect(screen.getByText('To start your own run, ask the author for an invite.')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'How it works' })).toHaveAttribute('href', expect.stringContaining('#what-it-does'))
    expect(screen.getByRole('link', { name: 'Design decisions' })).toHaveAttribute('href', expect.stringContaining('/docs/adr'))
    expect(screen.queryByRole('button', { name: /Start the run/ })).not.toBeInTheDocument()
    expect(screen.queryByTestId('in-flight')).toHaveTextContent('')
    ;['clients-number', 'gateway-number', 'ledger-number'].forEach((id) => expect(screen.getByTestId(id)).toHaveTextContent(''))
  })
})
