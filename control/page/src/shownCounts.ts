import type { BankView } from './api/client'

type BankCounts = { accepted: number; posted: number; rejected: number }

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

export type BankCounters = { accepted: number; posted: number; rejected: number; movedCents: number; requested: number | null; refused: number | null }

export function bankCounters(bank: BankView): BankCounters {
  const t = bank.totals
  return {
    accepted: shownAccepted(t),
    posted: t.posted,
    rejected: t.rejected,
    movedCents: t.postedAmountCents,
    requested: bank.clients?.requested ?? null,
    refused: bank.clients?.refused ?? null,
  }
}
