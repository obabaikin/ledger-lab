import { vi } from 'vitest'
import type { RunReport, RunView, SessionView } from '../api/client'

export type Answer = { status: number; body?: unknown; headers?: Record<string, string> }
export type Call = { method: string; path: string; body: unknown }

export function fakeControl(answers: Record<string, Answer | (() => Answer)>) {
  const calls: Call[] = []
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
    const method = init?.method ?? 'GET'
    const path = String(input)
    calls.push({ method, path, body: init?.body ? JSON.parse(String(init.body)) : undefined })
    const found = answers[`${method} ${path}`]
    if (found === undefined) throw new TypeError('Failed to fetch')
    const answer = typeof found === 'function' ? found() : found
    return new Response(answer.body === undefined ? null : JSON.stringify(answer.body), {
      status: answer.status,
      headers: { ...(answer.body === undefined ? {} : { 'Content-Type': 'application/json' }), ...answer.headers },
    })
  })
  return calls
}

export const SESSION_ID = '0b9f2a1e-6c1d-4a57-9d55-1f2e3a4b5c6d'
export const RUN_ID = '7d4c3b2a-1f0e-4d9c-8b7a-6f5e4d3c2b1a'

export const session = (changes: Partial<SessionView> = {}): SessionView => ({
  sessionId: SESSION_ID,
  inviteValid: true,
  runsPerHour: 10,
  runsLastHour: 2,
  tpsOptions: [100, 500, 1000],
  durationOptions: [10, 20, 30],
  chaosAvailable: false,
  chaosReadyInSeconds: 0,
  ...changes,
})

export const run = (changes: Partial<RunView> = {}): RunView => ({
  runId: RUN_ID,
  state: 'QUEUED',
  tps: 100,
  durationSeconds: 10,
  createdAt: '2026-10-12T09:00:00Z',
  admittedAt: null,
  finishedAt: null,
  position: 1,
  startedAt: null,
  stopRequested: false,
  closedAt: null,
  booksFinalAt: null,
  result: null,
  failure: null,
  live: null,
  ...changes,
})

const noIds = { count: 0, transferIds: [] }

export const report = (changes: Partial<RunReport> = {}): RunReport => ({
  result: 'BALANCED',
  reasons: [],
  ledgerBalanced: true,
  ledgerFailedChecks: [],
  bankViewsAgree: true,
  clientBalanced: true,
  statementFinal: true,
  client: { requested: 1000, acknowledged: 990, failed: 5, unknown: 5, acknowledgedAmountCents: 99000 },
  gateway: { accepted: 995, published: 995, posted: 980, rejected: 15 },
  ledger: { posted: 980, rejected: 15, openingTotalCents: 10_000_000, balanceTotalCents: 10_000_000, debitsCents: 98_000, creditsCents: 98_000 },
  bankLatencyP50Ms: 12.4,
  bankLatencyP99Ms: 480.5,
  rejectionsByReason: { BLOCKED_ACCOUNT: 15 },
  breaks: {
    notInJournal: noIds,
    acknowledgedNotInStatement: noIds,
    failedButInStatement: noIds,
    payloadMismatch: noIds,
    statusMismatch: noIds,
  },
  unknownResolved: { accepted: noIds, notAccepted: noIds },
  clientLatencyP50Ms: 20.1,
  clientLatencyP99Ms: 510.2,
  stats: null,
  projectionMatchesBooks: null,
  ledgerDuplicatesAtLeast: null,
  notes: [],
  chaos: [],
  ...changes,
})
