import { vi } from 'vitest'
import type { BankView, PeriodReport, PeriodView, SeriesView, SessionView } from '../api/client'

export type Answer = { status: number; body?: unknown; headers?: Record<string, string> }
type Call = { method: string; path: string; body: unknown }

export function fakeControl(answers: Record<string, Answer | (() => Answer | Promise<Answer>)>) {
  const calls: Call[] = []
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
    const method = init?.method ?? 'GET'
    const path = String(input)
    calls.push({ method, path, body: init?.body ? JSON.parse(String(init.body)) : undefined })
    const found = answers[`${method} ${path}`]
    if (found === undefined) throw new TypeError('Failed to fetch')
    const answer = typeof found === 'function' ? await found() : found
    return new Response(answer.body === undefined ? null : JSON.stringify(answer.body), {
      status: answer.status,
      headers: { ...(answer.body === undefined ? {} : { 'Content-Type': 'application/json' }), ...answer.headers },
    })
  })
  return calls
}

export const SESSION_ID = '0b9f2a1e-6c1d-4a57-9d55-1f2e3a4b5c6d'
export const PERIOD_START = '2026-11-11T09:00:00Z'

export const session = (changes: Partial<SessionView> = {}): SessionView => ({
  sessionId: SESSION_ID,
  inviteValid: true,
  loadTpsOptions: [900, 1000, 2000],
  defaultLoadTps: 1000,
  loadDurationOptions: [10, 20, 30],
  defaultLoadSeconds: 10,
  loadReadyInSeconds: 0,
  chaosAvailable: true,
  chaosReadyInSeconds: 0,
  ...changes,
})

export const bank = (changes: Partial<BankView> = {}): BankView => ({
  startedAt: '2026-11-10T08:00:00Z',
  periodMinutes: 60,
  currentPeriod: { start: '2026-11-11T10:00:00Z', end: '2026-11-11T11:00:00Z', closesAt: '2026-11-11T11:01:00Z' },
  totals: { accepted: 1000, posted: 990, rejected: 5, postedAmountCents: 99_000, rejectionsByReason: { INSUFFICIENT_FUNDS: 4, BLOCKED_ACCOUNT: 1 } },
  books: { accounts: 100_000, openingTotalCents: 1_000_000_000_00, balanceTotalCents: 1_000_000_000_00, balanced: true, readAt: '2026-11-11T10:30:00Z' },
  clients: {
    since: '2026-11-11T07:15:00Z',
    baseTps: 400,
    targetTps: 400,
    pulse: null,
    requested: 1100,
    acknowledged: 990,
    gaveUp: 3,
    refused: 0,
    retries: 0,
    inFlight: 5,
    total: { sent: 917_000, acknowledged: 916_000, gaveUp: 600, unanswered: 400 },
    restarted: true,
  },
  pods: null,
  slo: { target: 0.999, windowDays: 30, measuredMinutes: 1000, badMinutes: 17, budgetMinutes: 43, plannedMinutes: 12, outsideMinutes: 2, availability: 0.9983, busyShare: 0.004, days: [] },
  load: { baseTps: 400, nextChangeAt: '2026-11-11T10:35:00Z' },
  posting: {
    windowDays: 30,
    since: null,
    judged: 1_000_000,
    planned: 12_000,
    outside: 3_400,
    targets: [
      { thresholdMs: 3000, target: 0.95, within: 999_700, share: 0.9997, budgetUsed: 0.006, days: [] },
      { thresholdMs: 5000, target: 0.99, within: 999_800, share: 0.9998, budgetUsed: 0.02, days: [] },
    ],
  },
  maintenance: null,
  chaos: { available: true, readyInSeconds: 0, recent: [] },
  readAt: '2026-11-11T10:30:00Z',
  ...changes,
})

export const period = (changes: Partial<PeriodView> = {}): PeriodView => ({
  start: PERIOD_START,
  end: '2026-11-11T10:00:00Z',
  state: 'RECONCILED',
  closedAt: '2026-11-11T10:01:00Z',
  booksFinalAt: '2026-11-11T10:01:05Z',
  verdict: 'BALANCED',
  failure: null,
  finishedAt: '2026-11-11T10:02:00Z',
  numbers: { accepted: 1_440_000, posted: 1_430_000, rejected: 10_000, postedAmountCents: 1_440_000_000, rejectionsByReason: { INSUFFICIENT_FUNDS: 10_000 } },
  ...changes,
})

const noIds = { count: 0, transferIds: [] }
const noSides = { count: 0, examples: [] }

export const report = (changes: Partial<PeriodReport> = {}): PeriodReport => ({
  verdict: 'BALANCED',
  mismatches: [],
  gateway: { accepted: 995, entryCount: 995, published: 995, posted: 980, rejected: 15 },
  ledger: { postedTransfers: 980, rejectedTransfers: 15, poolPostings: 0 },
  rejectionsByReason: { BLOCKED_ACCOUNT: 15 },
  ledgerCheck: {
    balanced: true,
    failedChecks: [],
    debitsCents: 98_000,
    creditsCents: 98_000,
    journalEntries: 1960,
    transfersWithInvalidJournal: 0,
    invalidJournalExamples: [],
    accountsCompared: true,
    accountsOutOfBalance: 0,
    accountExamples: [],
    startedAt: '2026-11-11T11:10:02Z',
    madeAt: '2026-11-11T11:10:09Z',
  },
  books: { accounts: 100_000, openingTotalCents: 10_000_000, balanceTotalCents: 10_000_000, balanced: true },
  client: {
    periodStart: PERIOD_START,
    balanced: true,
    statementFinal: true,
    acceptedByJournal: 995,
    statement: { rows: 995, amountCents: 99_000, byStatus: { POSTED: 980, REJECTED: 15 }, unknownToClient: 0 },
    breaks: { notInJournal: noIds, acknowledgedNotInStatement: noIds, failedButInStatement: noIds, payloadMismatch: noSides, statusMismatch: noSides },
    unknownResolved: { accepted: 2, notAccepted: 1 },
  },
  stats: null,
  notes: [],
  chaos: [],
  load: null,
  ...changes,
})

export const series = (changes: Partial<SeriesView> = {}): SeriesView => ({
  from: '2026-11-11T10:00:00Z',
  stepSeconds: 15,
  points: 3,
  series: [
    { name: 'ACCEPTED_PER_SECOND', values: [400, 410, null] },
    { name: 'POSTED_PER_SECOND', values: [398, 409, null] },
  ],
  ...changes,
})

export const SERIES = 'GET /api/bank/series?range=2h'

export const bankAnswers = (): Record<string, Answer> => ({
  'GET /api/bank': { status: 200, body: bank() },
  'GET /api/bank/periods?limit=10': { status: 200, body: { periods: [] } },
  [SERIES]: { status: 200, body: series() },
})
