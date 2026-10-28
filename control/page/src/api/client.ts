import type { components } from './schema'

type Schemas = components['schemas']
export type SessionView = Schemas['SessionView']
export type RunView = Schemas['RunView']
export type RunReport = Schemas['RunReport']
type RunState = RunView['state']
export type LiveView = Schemas['LiveView']
export type PodView = Schemas['PodView']
export type ChaosInRun = Schemas['ChaosInRun']
export type SecondStats = Schemas['SecondStats']
export type SiteView = Schemas['SiteView']

export const FINAL_STATES: ReadonlySet<RunState> = new Set(['RECONCILED', 'FAILED', 'EXPIRED'])

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
  runs: (sessionId: string) => call<Schemas['RunList']>(`/api/sessions/${sessionId}/runs`),
  startRun: (sessionId: string, request: Schemas['StartRunRequest']) =>
    call<RunView>(`/api/sessions/${sessionId}/runs`, post(request)),
  stopRun: (sessionId: string, runId: string) => call<RunView>(`/api/sessions/${sessionId}/runs/${runId}/stop`, post()),
  run: (sessionId: string, runId: string) => call<RunView>(`/api/sessions/${sessionId}/runs/${runId}`),
  chaos: (sessionId: string) => call<Schemas['ChaosView']>(`/api/sessions/${sessionId}/chaos`, post()),
  report: (sessionId: string, runId: string) => call<RunReport>(`/api/sessions/${sessionId}/runs/${runId}/report`),
  site: () => call<SiteView>('/api/site'),
  requestInvite: (request: Schemas['InviteRequest']) => call<unknown>('/api/invite-requests', post(request)),
}
