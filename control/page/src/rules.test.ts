import { describe, expect, it } from 'vitest'
import { age } from './BankPage'
import { crashBands, segments, seriesPoints } from './Charts'
import { podState, podStates } from './Diagram'
import { flowRates, stopLedgerWires } from './FlowDots'
import { NO_DATA, shown } from './format'
import { nextReadyAt, waitShown } from './readyCountdown'
import { bankCounters, inFlight, shownAccepted } from './shownCounts'
import { between } from './smooth'
import { bank, series } from './test/fakeControl'
import { en } from './texts/en'

describe('live counts', () => {
  it('take in flight from one snapshot and never below 0', () => {
    expect(inFlight({ accepted: 1000, posted: 990, rejected: 5 })).toBe(5)
    expect(inFlight({ accepted: 990, posted: 990, rejected: 5 })).toBe(0)
  })

  it('show accepted at least as booked and rejected together', () => {
    expect(shownAccepted({ accepted: 990, posted: 990, rejected: 5 })).toBe(995)
    expect(shownAccepted({ accepted: 1000, posted: 990, rejected: 5 })).toBe(1000)
  })

  it("leave the clients' counts unknown until the generator answered, the bank's known", () => {
    expect(bankCounters(bank({ clients: null }))).toMatchObject({ accepted: 1000, sent: null, refused: null })
  })

  it("count what the clients sent from the bank's start, and leave it unknown while the generator cannot read its journal", () => {
    expect(bankCounters(bank()).sent).toBe(917_000)
    expect(bankCounters(bank({ clients: { ...bank().clients!, total: null } })).sent).toBeNull()
  })
})

describe('numbers on screen', () => {
  it('shows a dash, not 0, for a number not read yet', () => {
    expect(shown(null)).toBe(NO_DATA)
    expect(shown(undefined)).toBe(NO_DATA)
    expect(shown(0)).toBe('0')
  })

  it('moves towards the new snapshot without passing it, keeping in flight at or above 0 at every step', () => {
    const from = { accepted: 100, posted: 50, rejected: 1 }
    const to = { accepted: 200, posted: 151, rejected: 2 }
    expect(between(from, to, 0.5)).toEqual({ accepted: 150, posted: 100, rejected: 1 })
    expect(between(from, to, 2)).toEqual(to)

    const before = { accepted: 1003, posted: 997, rejected: 3 }
    const after = { accepted: 2011, posted: 2004, rejected: 7 }
    for (let f = 0; f <= 1; f += 0.01) {
      const v = between(before, after, f)
      expect(v.accepted - v.posted - v.rejected).toBeGreaterThanOrEqual(0)
    }
  })

  it('jumps to a lower snapshot instead of counting down', () => {
    expect(between({ accepted: 500 }, { accepted: 10 }, 0.1)).toEqual({ accepted: 10 })
  })

  it('keeps a part nobody has read unknown, and starts it from its first value', () => {
    expect(between({ requested: 500 }, { requested: null }, 0.5)).toEqual({ requested: null })
    expect(between({ requested: null }, { requested: 800 }, 0.5)).toEqual({ requested: 800 })
    expect(between(null, { accepted: 42 }, 0)).toEqual({ accepted: 42 })
  })
})

describe('countdown of a button', () => {
  const NOW = 1_000_000

  it('is not stretched by the server rounding up each second', () => {
    let at = nextReadyAt(0, 60, NOW)
    for (let t = 1; t < 60; t++) at = nextReadyAt(at, 60 - t + 1, NOW + t * 1000 + 300)
    expect(at).toBe(NOW + 60_000)
  })

  it('ends when the server says ready', () => {
    expect(nextReadyAt(NOW + 5_000, 0, NOW)).toBe(0)
  })

  it('moves to a later deadline, as after a crash from another session', () => {
    expect(nextReadyAt(NOW + 5_000, 60, NOW)).toBe(NOW + 60_000)
  })
})

describe('button wait', () => {
  const NOW = 1_000_000

  it('stays on 1 s while the server has not said ready, though the page counted down', () => {
    expect(waitShown(1, NOW - 500, 0, NOW)).toBe(1)
  })

  it('shows the button only once the server says ready', () => {
    expect(waitShown(0, NOW + 5_000, 0, NOW)).toBeNull()
  })

  it('counts down by the page between the server answers', () => {
    expect(waitShown(60, NOW + 42_300, 0, NOW)).toBe(43)
  })

  it('holds a refusal until its Retry-After, though the session says ready', () => {
    expect(waitShown(0, 0, NOW + 4_200, NOW)).toBe(5)
    expect(waitShown(0, 0, NOW - 1, NOW)).toBeNull()
  })
})

describe('latency line', () => {
  it('breaks at a second without a value instead of dropping to 0', () => {
    expect(segments([[1, 12], [2, null], [3, 14], [4, 15]])).toEqual([[[1, 12]], [[3, 14], [4, 15]]])
  })

})

describe('charts', () => {
  it('place each value at its instant from the start of the range', () => {
    expect(seriesPoints(series(), 'ACCEPTED_PER_SECOND')).toEqual([[0, 400], [15, 410], [30, null]])
  })

  it('draw nothing for a series monitoring did not send', () => {
    expect(seriesPoints({ ...series(), series: [] }, 'POSTED_PER_SECOND')).toEqual([])
  })

  it('mark a crash within the range one step wide, and leave out one outside it', () => {
    const at = (iso: string) => ({ at: iso, instances: ['ledger-0'] })
    expect(crashBands([at('2026-11-11T10:00:20Z'), at('2026-11-11T09:59:00Z')], series())).toEqual([[20, 30]])
  })
})

describe('age of the bank', () => {
  const MINUTE = 60_000

  it('is in minutes for the first hour, hours and minutes for the first day, then days and hours', () => {
    expect(age(0, 42 * MINUTE)).toBe('42 min')
    expect(age(0, 125 * MINUTE)).toBe('2 h 5 min')
    expect(age(0, (3 * 24 * 60 + 61) * MINUTE)).toBe('3 d 1 h')
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
    expect(podStates(null)).toEqual([{ name: en.bank.scheme.pods.unknown, state: 'unknown' }])
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

  it('do not run before there are two snapshots', () => {
    expect(flowRates(null, flow(1000), 1)).toEqual([0, 0, 0, 0, 0, 0, 0])
  })
})

describe('dots after a crash', () => {
  const rates = [100, 100, 100, 100, 1, 100, 100]

  it('stop at once on the ledger wires while the interval began before the crash', () => {
    expect(stopLedgerWires(rates, 5_000, 4_500)).toEqual([100, 100, 0, 0, 0, 0, 100])
  })

  it('follow the snapshots again once a whole interval lies after the crash', () => {
    expect(stopLedgerWires(rates, 5_000, 5_200)).toEqual(rates)
  })

  it('run as measured without a crash', () => {
    expect(stopLedgerWires(rates, null, 4_500)).toEqual(rates)
  })
})
