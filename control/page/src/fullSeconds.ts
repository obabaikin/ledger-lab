import type { SecondStats } from './api/client'

const MS_PER_SECOND = 1000

/**
 * Seconds of the bank's series that are over: not the one still running at the snapshot, and not the one in which the
 * clients' window closed. A part of a second drawn as a whole one looks like a drop to the end.
 */
export function fullBankSeconds(series: SecondStats[], snapshotAt: number, clientsEndAt: number | null): SecondStats[] {
  return series.filter((s) => {
    const from = Date.parse(s.second)
    const to = from + MS_PER_SECOND
    const closesWindow = clientsEndAt !== null && from < clientsEndAt && clientsEndAt < to
    return to <= snapshotAt && !closesWindow
  })
}

/** The clients' seconds within the run's duration; while they send, the last one is still being counted. */
export function fullClientSeconds(perSecond: number[], durationSeconds: number, sending: boolean): number[] {
  return perSecond.slice(0, Math.min(durationSeconds, sending ? perSecond.length - 1 : perSecond.length))
}
