import { screen, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { bank, fakeControl, series, type Answer } from './test/fakeControl'
import { renderAt } from './test/render'
import { en } from './texts/en'

vi.mock('./FlowDots', async (original) => ({
  ...(await original<typeof import('./FlowDots')>()),
  FlowDots: (props: { rates: number[] }) => <g data-testid="flow-dots" data-rates={props.rates.join()} />,
}))

const POLLS_UNTIL_CHANGE_MS = 3000
const STEP_MS = 1000
const REQUESTED_PER_STEP = 100
const READ_FROM = Date.parse('2026-11-11T10:30:00Z')

/** Every answer is a new reading a second after the one before, with a hundred more transfers sent. */
function bankThatMoves() {
  let readings = 0
  const state = { up: true }
  fakeControl({
    'GET /api/bank': (): Answer => {
      readings += 1
      if (!state.up) return { status: 502, body: { error: 'bad gateway' } }
      const base = bank()
      return {
        status: 200,
        body: bank({
          readAt: new Date(READ_FROM + readings * STEP_MS).toISOString(),
          clients: { ...base.clients!, requested: base.clients!.requested + readings * REQUESTED_PER_STEP },
        }),
      }
    },
    'GET /api/bank/periods?limit=10': { status: 200, body: { periods: [] } },
    'GET /api/bank/series': { status: 200, body: series() },
  })
  return state
}

const rates = () => (screen.getByTestId('flow-dots').getAttribute('data-rates') ?? '').split(',').map(Number)
const moving = () => rates().some((rate) => rate > 0)
const still = () => rates().every((rate) => rate === 0)

describe('the dots of the scheme', () => {
  it('stand still while the connection is lost, and the numbers stay the last ones read', async () => {
    const bankState = bankThatMoves()
    renderAt('/')
    await waitFor(() => expect(moving()).toBe(true), { timeout: POLLS_UNTIL_CHANGE_MS })
    const sent = screen.getByTestId('clients-number').textContent

    bankState.up = false

    expect(await screen.findByText(en.connection.lost, {}, { timeout: POLLS_UNTIL_CHANGE_MS })).toBeInTheDocument()
    expect(still()).toBe(true)
    expect(screen.getByTestId('clients-number').textContent).toBe(sent)
  })

  it('move again from the second fresh reading after the connection is back, not at the rate across the gap', async () => {
    const bankState = bankThatMoves()
    renderAt('/')
    await waitFor(() => expect(moving()).toBe(true), { timeout: POLLS_UNTIL_CHANGE_MS })
    bankState.up = false
    await screen.findByText(en.connection.lost, {}, { timeout: POLLS_UNTIL_CHANGE_MS })

    bankState.up = true

    await waitFor(() => expect(screen.queryByText(en.connection.lost)).not.toBeInTheDocument(), { timeout: POLLS_UNTIL_CHANGE_MS })
    expect(still()).toBe(true)
    await waitFor(() => expect(moving()).toBe(true), { timeout: POLLS_UNTIL_CHANGE_MS })
  }, 15_000)
})
