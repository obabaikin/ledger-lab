import type { components } from './schema'

type Schemas = components['schemas']
export type SessionView = Schemas['SessionView']
export type BankView = Schemas['BankView']
export type PeriodView = Schemas['PeriodView']
export type PeriodDetails = Schemas['PeriodDetails']
export type PeriodReport = Schemas['PeriodReport']
export type SeriesView = Schemas['SeriesView']
export type SeriesName = Schemas['Series']['name']
export type PodView = Schemas['PodView']
export type ChaosEvent = Schemas['ChaosEvent']
export type SiteView = Schemas['SiteView']

const PERIODS_LISTED = 10

/** The ranges control serves its charts for (`control.series.ranges`). */
export const CHART_RANGES = ['5m', '30m', '2h'] as const
export type ChartRange = (typeof CHART_RANGES)[number]
export const DEFAULT_CHART_RANGE: ChartRange = '2h'

export class ApiFailure extends Error {
  constructor(
    readonly status: number,
    readonly error: string,
    readonly retryAfterSeconds: number | null,
  ) {
    super(error)
  }
}

async function call<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(path, {
    ...init,
    headers: { Accept: 'application/json', ...(init?.body ? { 'Content-Type': 'application/json' } : {}) },
  })
  const body: unknown = response.headers.get('Content-Type')?.includes('json') ? await response.json() : null
  if (!response.ok) {
    const error = (body as Schemas['ApiError'] | null)?.error ?? response.statusText
    const retryAfter = response.headers.get('Retry-After')
    throw new ApiFailure(response.status, error, retryAfter === null ? null : Number(retryAfter))
  }
  return body as T
}

const post = (body?: unknown): RequestInit => ({ method: 'POST', body: body === undefined ? undefined : JSON.stringify(body) })

export const api = {
  openSession: (token: string) => call<Schemas['SessionCreated']>('/api/sessions', post({ token })),
  session: (sessionId: string) => call<SessionView>(`/api/sessions/${sessionId}`),
  load: (sessionId: string, request: Schemas['LoadRequest']) => call<Schemas['Pulse']>(`/api/sessions/${sessionId}/load`, post(request)),
  chaos: (sessionId: string) => call<ChaosEvent>(`/api/sessions/${sessionId}/chaos`, post()),
  bank: () => call<BankView>('/api/bank'),
  periods: () => call<Schemas['PeriodList']>(`/api/bank/periods?limit=${PERIODS_LISTED}`),
  period: (start: string) => call<PeriodDetails>(`/api/bank/periods/${encodeURIComponent(start)}`),
  series: (range: ChartRange) => call<SeriesView>(`/api/bank/series?range=${range}`),
  site: () => call<SiteView>('/api/site'),
  requestInvite: (request: Schemas['InviteRequest']) => call<unknown>('/api/invite-requests', post(request)),
  deleteAddress: (token: string) => call<Schemas['AddressDeletionAccepted']>('/api/address-deletions', post({ token })),
}
