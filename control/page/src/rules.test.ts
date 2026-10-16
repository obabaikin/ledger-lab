import { describe, expect, it } from 'vitest'
import { NO_DATA, shown } from './format'
import { booksFinal, PHASES, phaseOf } from './phase'
import { inFlight, liveCounters, shownAccepted } from './shownCounts'
import { crashBands, segments } from './Charts'
import { podState, podStates } from './Diagram'
import { between } from './smooth'
import { otherRunInProgress, runToShow } from './selectRun'
import { run } from './test/fakeControl'
import { flowRates, stopLedgerWires } from './FlowDots'
import { fullBankSeconds, fullClientSeconds } from './fullSeconds'
import { chaosWaitShown, nextChaosReadyAt } from './chaosReady'

describe('phases', () => {
  it.each([
    ['QUEUED', 'Queued'],
    ['CREATED', 'Queued'],
    ['RUNNING', 'Clients sending'],
    ['DRAINING', 'Bank finishing'],
    ['RECONCILED', 'Verdict'],
    ['FAILED', 'Verdict'],
    ['EXPIRED', 'Verdict'],
  ] as const)('a run %s is in %s', (state, phase) => {
    expect(PHASES[phaseOf({ state })]).toBe(phase)
  })

  it('are four, books final being a mark within Bank finishing', () => {
    expect(PHASES).toEqual(['Queued', 'Clients sending', 'Bank finishing', 'Verdict'])
    expect(PHASES[phaseOf({ state: 'DRAINING' })]).toBe('Bank finishing')
  })

  it('mark the books final only once control saw them final', () => {
    expect(booksFinal({ state: 'DRAINING', booksFinalAt: null })).toBe(false)
    expect(booksFinal({ state: 'DRAINING', booksFinalAt: '2026-10-15T10:00:00Z' })).toBe(true)
  })
})

describe('live counts', () => {
  const live = (changes: Record<string, unknown> = {}) =>
    ({
      stats: { at: '2026-10-15T10:00:05Z', totals: { accepted: 1000, posted: 990, rejected: 5, postedAmountCents: 99_000 }, series: [] },
      clients: { requested: 1100 },
      ...changes,
    }) as never

  it('take in flight from one snapshot and never below 0', () => {
    expect(inFlight({ accepted: 1000, posted: 990, rejected: 5 })).toBe(5)
    expect(inFlight({ accepted: 990, posted: 990, rejected: 5 })).toBe(0)
  })

  it('show accepted at least as booked and rejected together', () => {
    expect(shownAccepted({ accepted: 990, posted: 990, rejected: 5 })).toBe(995)
    expect(shownAccepted({ accepted: 1000, posted: 990, rejected: 5 })).toBe(1000)
  })

  it('leave the clients\' count unknown, not -1 or 0, while the generator has not answered', () => {
    expect(liveCounters(live({ clients: null }))?.requested).toBeNull()
    expect(liveCounters(live())?.requested).toBe(1100)
  })

  it('are unknown as a whole without a projection snapshot', () => {
    expect(liveCounters(live({ stats: null }))).toBeNull()
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

  it('keeps a part nobody has read unknown, and starts it from its first value', () => {
    expect(between({ requested: 500 }, { requested: null }, 0.5)).toEqual({ requested: null })
    expect(between({ requested: null }, { requested: 800 }, 0.5)).toEqual({ requested: 800 })
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

describe('latency line', () => {
  it('breaks at a second without a value instead of dropping to 0', () => {
    expect(segments([[1, 12], [2, null], [3, 14], [4, 15]])).toEqual([[[1, 12]], [[3, 14], [4, 15]]])
  })

  it('is one line without gaps', () => {
    expect(segments([[1, 12], [2, 13]])).toEqual([[[1, 12], [2, 13]]])
  })
})

describe('crash band', () => {
  // Run 1eaad4d9 of 2026-10-16: crash at 7.963 s; a point sits at the end of its second.
  const booked: [number, number][] = [[6, 495], [7, 507], [8, 488], [9, 87], [10, 0], [11, 0], [12, 0], [13, 0], [14, 0], [15, 0], [16, 16], [17, 330]]
  const crash = { offsetMs: 7963, instances: ['ledger-0', 'ledger-1', 'ledger-2'] }

  it('lasts until the ledger books again, past the bookings it still made while going down', () => {
    expect(crashBands([crash], booked, 32)).toEqual([[7.963, 16]])
  })

  it('lasts to the end when the ledger has not booked again yet', () => {
    expect(crashBands([crash], booked.slice(0, 8), 32)).toEqual([[7.963, 32]])
  })

  it('is one second wide when the ledger never stopped booking', () => {
    const [[from, to]] = crashBands([crash], [[8, 500], [9, 480], [10, 510]], 32) as [[number, number]]
    expect(from).toBe(7.963)
    expect(to).toBeCloseTo(8.963)
  })
})

describe('instances', () => {
  const pod = (up: boolean, partitions: number | null) => ({ instance: 'ledger-0', job: 'ledger', up, partitions })

  it('are down when Prometheus got no answer', () => {
    expect(podState(pod(false, 4))).toBe('down')
  })

  it('are starting while up without partitions', () => {
    expect(podState(pod(true, 0))).toBe('starting')
  })

  it('are consuming with partitions, and a service without partitions is consuming once up', () => {
    expect(podState(pod(true, 4))).toBe('consuming')
    expect(podState({ ...pod(true, null), job: 'gateway' })).toBe('consuming')
  })

  it('show no data without an answer from Prometheus', () => {
    expect(podStates(null)).toEqual([{ name: 'no data', state: 'unknown' }])
  })
})

describe('dots on the wires', () => {
  const flow = (n: number, finished = n, rejected = 0) => ({ requested: n, accepted: n, finished, rejected, acknowledged: n })

  it('run at the rate each count grew between two snapshots', () => {
    const before = { requested: 1000, accepted: 950, finished: 900, rejected: 10, acknowledged: 880 }
    const after = { requested: 3000, accepted: 2850, finished: 1900, rejected: 30, acknowledged: 1780 }
    expect(flowRates(before, after, 2)).toEqual([1000, 950, 500, 500, 10, 500, 450])
  })

  it('stand still, not backwards, where a count went down', () => {
    expect(flowRates(flow(2000), flow(1000), 1)).toEqual([0, 0, 0, 0, 0, 0, 0])
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

  it('hides while a run only waits in the queue', () => {
    expect(otherRunInProgress([run({ runId: 'q', state: 'QUEUED' }), done], done)).toBeNull()
  })

  it('hides when nothing is in progress', () => {
    expect(otherRunInProgress([done], done)).toBeNull()
  })
})
