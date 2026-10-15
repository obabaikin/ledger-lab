import { describe, expect, it } from 'vitest'
import { NO_DATA, shown } from './format'
import { PHASES, phaseOf } from './phase'
import { between } from './smooth'
import { otherRunInProgress, runToShow } from './selectRun'
import { run } from './test/fakeControl'
import { flowRates, stopLedgerWires } from './FlowDots'
import { fullBankSeconds, fullClientSeconds } from './fullSeconds'
import { chaosWaitShown, nextChaosReadyAt } from './chaosReady'

describe('phases', () => {
  it.each([
    ['QUEUED', null, 'Queued'],
    ['CREATED', null, 'Queued'],
    ['RUNNING', null, 'Clients sending'],
    ['DRAINING', null, 'Bank finishing'],
    ['DRAINING', '2026-10-15T10:00:00Z', 'Reconciling'],
    ['RECONCILED', '2026-10-15T10:00:00Z', 'Verdict'],
    ['FAILED', null, 'Verdict'],
    ['EXPIRED', null, 'Verdict'],
  ] as const)('a run %s with booksFinalAt %s is in %s', (state, booksFinalAt, phase) => {
    expect(PHASES[phaseOf({ state, booksFinalAt })]).toBe(phase)
  })
})

describe('numbers on screen', () => {
  it('shows a dash, not 0, for a number not read yet', () => {
    expect(shown(null)).toBe(NO_DATA)
    expect(shown(undefined)).toBe(NO_DATA)
    expect(shown(0)).toBe('0')
  })

  it('moves towards the new snapshot without passing it', () => {
    const from = { accepted: 100, posted: 50, rejected: 1 }
    const to = { accepted: 200, posted: 151, rejected: 2 }

    expect(between(from, to, 0.5)).toEqual({ accepted: 150, posted: 100, rejected: 1 })
    expect(between(from, to, 2)).toEqual(to)
  })

  it('keeps in flight at or above 0 at every step between two snapshots', () => {
    const from = { accepted: 1003, posted: 997, rejected: 3 }
    const to = { accepted: 2011, posted: 2004, rejected: 7 }
    for (let f = 0; f <= 1; f += 0.01) {
      const v = between(from, to, f)
      expect(v.accepted - v.posted - v.rejected).toBeGreaterThanOrEqual(0)
    }
  })

  it('jumps to a lower snapshot instead of counting down', () => {
    expect(between({ accepted: 500 }, { accepted: 10 }, 0.1)).toEqual({ accepted: 10 })
  })

  it('starts from the snapshot itself when nothing was shown before', () => {
    expect(between(null, { accepted: 42 }, 0)).toEqual({ accepted: 42 })
  })
})

describe('chaos countdown', () => {
  const NOW = 1_000_000

  it('takes the deadline from the server when none is known', () => {
    expect(nextChaosReadyAt(0, 60, NOW)).toBe(NOW + 60_000)
  })

  it('is not stretched by the server rounding up each second', () => {
    let at = nextChaosReadyAt(0, 60, NOW)
    for (let t = 1; t < 60; t++) at = nextChaosReadyAt(at, 60 - t + 1, NOW + t * 1000 + 300)
    expect(at).toBe(NOW + 60_000)
  })

  it('ends when the server says ready', () => {
    expect(nextChaosReadyAt(NOW + 5_000, 0, NOW)).toBe(0)
  })

  it('moves to a later deadline, as after a crash from another session', () => {
    expect(nextChaosReadyAt(NOW + 5_000, 60, NOW)).toBe(NOW + 60_000)
  })
})

describe('crash button', () => {
  const NOW = 1_000_000

  it('stays on 1 s while the server has not said ready, though the page counted down', () => {
    expect(chaosWaitShown(1, NOW - 500, NOW)).toBe(1)
  })

  it('shows the button only once the server says ready', () => {
    expect(chaosWaitShown(0, NOW + 5_000, NOW)).toBeNull()
  })

  it('counts down by the page between the server answers', () => {
    expect(chaosWaitShown(60, NOW + 42_300, NOW)).toBe(43)
  })
})

describe('transfers per second', () => {
  const second = (iso: string, accepted: number) =>
    ({ second: iso, accepted, posted: accepted, rejected: 0, postedAmountCents: 0, bankP50Ms: null, bankP99Ms: null })
  const series = [second('2026-10-15T10:00:00Z', 1000), second('2026-10-15T10:00:01Z', 1000), second('2026-10-15T10:00:02Z', 410)]

  it('leaves out the second still running at the snapshot', () => {
    expect(fullBankSeconds(series, Date.parse('2026-10-15T10:00:02.600Z'), null).map((s) => s.accepted)).toEqual([1000, 1000])
  })

  it("leaves out the second in which the clients' window closed", () => {
    const ended = fullBankSeconds(series, Date.parse('2026-10-15T10:00:09Z'), Date.parse('2026-10-15T10:00:02.410Z'))
    expect(ended.map((s) => s.accepted)).toEqual([1000, 1000])
  })

  it('keeps a whole second with 0, as when the ledger is down', () => {
    const down = [second('2026-10-15T10:00:00Z', 1000), second('2026-10-15T10:00:01Z', 0)]
    expect(fullBankSeconds(down, Date.parse('2026-10-15T10:00:05Z'), null).map((s) => s.accepted)).toEqual([1000, 0])
  })

  it("leaves out the clients' second being counted and the slot past the duration", () => {
    expect(fullClientSeconds([1000, 1000, 365], 30, true)).toEqual([1000, 1000])
    expect(fullClientSeconds([1000, 1000, 1000, 0], 3, false)).toEqual([1000, 1000, 1000])
  })
})

describe('dots on the wires', () => {
  const flow = (n: number, finished = n, rejected = 0) => ({ requested: n, accepted: n, finished, rejected, acknowledged: n })

  it('run at the rate each count grew between two snapshots', () => {
    expect(flowRates(flow(1000, 900, 10), flow(3000, 1900, 30), 2)).toEqual([1000, 1000, 500, 500, 10, 500, 1000])
  })

  it('run back to the clients at the rate they came in when every transfer is acknowledged', () => {
    const rates = flowRates(flow(1000), flow(2000), 1)
    expect(rates[6]).toBe(rates[0])
  })

  it('stop on the wires to the ledger while it books nothing', () => {
    const rates = flowRates(flow(1000, 900), flow(2000, 900), 1)
    expect(rates[1]).toBe(1000)
    expect(rates[2]).toBe(0)
  })

  it('do not run before there are two snapshots', () => {
    expect(flowRates(null, flow(1000), 1)).toEqual([0, 0, 0, 0, 0, 0, 0])
  })
})

describe('dots after a crash', () => {
  const rates = [100, 100, 100, 100, 1, 100, 100]

  it('stop at once on the ledger wires while the interval began before the crash', () => {
    expect(stopLedgerWires(rates, 5_000, 4_500)).toEqual([100, 100, 0, 0, 0, 0, 100])
  })

  it('keep running from the clients into Kafka and back to the clients', () => {
    const stopped = stopLedgerWires(rates, 5_000, 4_500)
    expect([stopped[0], stopped[1], stopped[6]]).toEqual([100, 100, 100])
  })

  it('follow the snapshots again once a whole interval lies after the crash', () => {
    expect(stopLedgerWires(rates, 5_000, 5_200)).toEqual(rates)
  })

  it('run as measured without a crash', () => {
    expect(stopLedgerWires(rates, null, 4_500)).toEqual(rates)
  })
})

describe('run shown', () => {
  const queued = run({ runId: 'q2', state: 'QUEUED' })
  const queuedEarlier = run({ runId: 'q1', state: 'QUEUED' })
  const running = run({ runId: 'r', state: 'RUNNING' })
  const done = run({ runId: 'd', state: 'RECONCILED' })

  it('is the one running now, not a newer one still queued', () => {
    expect(runToShow([queued, queuedEarlier, running, done], null)?.runId).toBe('r')
  })

  it('is the newest when nothing is running', () => {
    expect(runToShow([queued, done], null)?.runId).toBe('q2')
  })

  it('is the one the viewer picked', () => {
    expect(runToShow([queued, running, done], 'd')?.runId).toBe('d')
  })

  it('is the oldest of two in progress, the one that started first', () => {
    const draining = run({ runId: 'dr', state: 'DRAINING' })
    expect(runToShow([running, draining], null)?.runId).toBe('dr')
  })
})

describe('link to the run in progress', () => {
  const running = run({ runId: 'r', state: 'RUNNING' })
  const done = run({ runId: 'd', state: 'RECONCILED' })

  it('shows while the viewer looks at another run', () => {
    expect(otherRunInProgress([running, done], done)?.runId).toBe('r')
  })

  it('hides while the viewer looks at the run in progress', () => {
    expect(otherRunInProgress([running, done], running)).toBeNull()
  })

  it('hides when nothing is in progress', () => {
    expect(otherRunInProgress([done], done)).toBeNull()
  })
})
