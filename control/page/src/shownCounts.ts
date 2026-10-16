import type { LiveView } from './api/client'

export type BankCounts = { accepted: number; posted: number; rejected: number }

/**
 * Accepted, booked and rejected come from one snapshot of the projection, which may see a result before its acceptance:
 * accepted is at least what was booked or rejected, so in flight is never below 0.
 */
export function shownAccepted(t: BankCounts): number {
  return Math.max(t.accepted, t.posted + t.rejected)
}

export function inFlight(t: BankCounts): number {
  return Math.max(0, t.accepted - t.posted - t.rejected)
}

export type LiveCounters = { accepted: number; posted: number; rejected: number; movedCents: number; requested: number | null }

export function liveCounters(live: LiveView | null): LiveCounters | null {
  if (!live?.stats) return null
  const t = live.stats.totals
  return {
    accepted: shownAccepted(t),
    posted: t.posted,
    rejected: t.rejected,
    movedCents: t.postedAmountCents,
    requested: live.clients?.requested ?? null,
  }
}
