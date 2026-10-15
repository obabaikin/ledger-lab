import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useParams } from 'react-router'
import { api, ApiFailure, FINAL_STATES, type LiveView, type RunReport, type RunView, type SessionView } from './api/client'
import { Charts, type RateLines } from './Charts'
import { Controls } from './Controls'
import { Diagram, NO_NUMBERS, type DiagramNumbers } from './Diagram'
import { flowRates, stopLedgerWires, type Flow } from './FlowDots'
import { euros, shown } from './format'
import { BuildInfo } from './BuildInfo'
import { Hero } from './Hero'
import { PHASES, phaseOf, phaseName, VERDICT_PHASE } from './phase'
import { otherRunInProgress, runToShow } from './selectRun'
import { useSmoothed } from './smooth'
import { usePolling } from './usePolling'
import { Verdict } from './Verdict'

export const POLL_INTERVAL_MS = 1000

/** What the page keeps of a run's live view after it ends: the report has no per-second series of the clients. */
type Seen = { runId: string; live: LiveView }

function liveCounters(live: LiveView | null) {
  if (!live?.stats) return null
  const t = live.stats.totals
  return { accepted: t.accepted, posted: t.posted, rejected: t.rejected, movedCents: t.postedAmountCents, requested: live.clients?.requested ?? -1 }
}

function Phases({ run }: { run: RunView | null }) {
  const now = run === null ? -1 : phaseOf(run)
  return (
    <div className="phases">
      {PHASES.map((p, k) => (
        <span key={p} className={`phase ${k < now || (k === now && k === VERDICT_PHASE) ? 'done' : k === now ? 'now' : ''}`}>
          {k < now ? '✓ ' : ''}
          {p}
          {k === 0 && run?.state === 'QUEUED' && run.position !== null ? ` · #${run.position}` : ''}
        </span>
      ))}
    </div>
  )
}

function RunList({ runs, selectedId, onSelect }: { runs: RunView[]; selectedId: string | null; onSelect: (id: string) => void }) {
  if (runs.length === 0) return null
  return (
    <section className="card">
      <h3>Runs of this session</h3>
      <div className="table-wrap">
        <table>
          <tbody>
            {runs.map((r) => (
              <tr key={r.runId} className={r.runId === selectedId ? 'selected' : ''} role="button" onClick={() => onSelect(r.runId)}>
                <td>{new Date(r.createdAt).toLocaleTimeString()}</td>
                <td>
                  {r.tps}/s × {r.durationSeconds} s
                </td>
                <td>{r.result ?? r.failure ?? r.state.toLowerCase()}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  )
}

export function SessionPage() {
  const { sessionId = '' } = useParams()
  const [session, setSession] = useState<SessionView | null>(null)
  const [missing, setMissing] = useState(false)
  const [runs, setRuns] = useState<RunView[]>([])
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [current, setCurrent] = useState<RunView | null>(null)
  const [seen, setSeen] = useState<Seen | null>(null)
  const [report, setReport] = useState<RunReport | null>(null)
  const [connectionLost, setConnectionLost] = useState(false)
  const [rateLines, setRateLines] = useState<RateLines>('all')
  const [smooth, setSmooth] = useState(true)

  const loadSession = useCallback(
    () =>
      api.session(sessionId).then(setSession, (e: unknown) => {
        if (e instanceof ApiFailure && e.status === 404) setMissing(true)
      }),
    [sessionId],
  )
  const loadRuns = useCallback(() => api.runs(sessionId).then(({ items }) => setRuns(items), () => undefined), [sessionId])

  useEffect(() => {
    void loadSession()
    void loadRuns()
  }, [loadSession, loadRuns])

  const selected = runToShow(runs, selectedId)
  const elsewhere = otherRunInProgress(runs, selected)
  const selectedRunId = selected?.runId ?? null
  const run = current?.runId === selectedRunId ? current : selected
  const final = run !== null && FINAL_STATES.has(run.state)

  useEffect(() => {
    setReport(null)
    setCurrent(null)
  }, [selectedRunId])

  const pollRun = useCallback(async () => {
    if (selectedRunId === null) return
    try {
      const view = await api.run(sessionId, selectedRunId)
      setConnectionLost(false)
      setCurrent(view)
      if (view.live) setSeen({ runId: view.runId, live: view.live })
      if (view.state === 'RECONCILED') {
        // 409 until the verdict is stored: the next poll asks again.
        setReport(await api.report(sessionId, view.runId).catch(() => null))
      }
    } catch (e) {
      setConnectionLost(!(e instanceof ApiFailure))
    }
  }, [sessionId, selectedRunId])

  const anyInProgress = runs.some((r) => !FINAL_STATES.has(r.state))
  usePolling(pollRun, POLL_INTERVAL_MS, selectedRunId !== null && (!final || (run?.state === 'RECONCILED' && report === null)))
  const chaosCountingDown = (session?.chaosReadyInSeconds ?? 0) > 0
  usePolling(() => Promise.all([loadRuns(), loadSession()]), POLL_INTERVAL_MS, anyInProgress || chaosCountingDown)

  const live = run?.live ?? null
  const seenLive = seen !== null && seen.runId === run?.runId ? seen.live : null
  const target = useMemo(() => liveCounters(live), [live])
  const counters = useSmoothed(target, POLL_INTERVAL_MS, smooth)
  const flow = useRef<{ at: number; value: Flow } | null>(null)
  const flowRead = useMemo(() => {
    if (!live?.stats || !live.clients) return undefined
    const t = live.stats.totals
    const value: Flow = {
      requested: live.clients.requested,
      accepted: t.accepted,
      finished: t.posted + t.rejected,
      rejected: t.rejected,
      acknowledged: live.clients.acknowledged,
    }
    const at = Date.parse(live.readAt)
    const prev = flow.current
    flow.current = { at, value }
    return { rates: flowRates(prev?.value ?? null, value, prev ? (at - prev.at) / 1000 : 0), from: prev?.at ?? null }
  }, [live])
  const [pressedCrashAt, setPressedCrashAt] = useState<number | null>(null)
  const lastChaos = live?.chaos.at(-1)
  const seenCrashAt = lastChaos && run?.startedAt ? Date.parse(run.startedAt) + lastChaos.offsetMs : null
  const crashAt = Math.max(pressedCrashAt ?? 0, seenCrashAt ?? 0) || null
  const rates = flowRead && stopLedgerWires(flowRead.rates, crashAt, flowRead.from)

  if (missing) {
    return (
      <main>
        <Hero />
        <p role="alert">This session does not exist. Open the invite link again to start a new one.</p>
      </main>
    )
  }
  if (session === null) return <main aria-busy="true" />

  const numbers: DiagramNumbers = report
    ? {
        requested: report.client?.requested ?? null,
        accepted: report.gateway.accepted,
        posted: report.ledger.posted,
        rejected: report.ledger.rejected,
        ledgerDuplicatesAtLeast: report.ledgerDuplicatesAtLeast,
        duplicatesSent: seenLive?.clients?.duplicatesSent ?? null,
      }
    : counters && live
      ? {
          requested: live.clients ? counters.requested : null,
          accepted: counters.accepted,
          posted: counters.posted,
          rejected: counters.rejected,
          ledgerDuplicatesAtLeast: live.ledgerDuplicatesAtLeast,
          duplicatesSent: live.clients?.duplicatesSent ?? null,
        }
      : NO_NUMBERS
  const series = report?.stats?.series ?? live?.stats?.series ?? seenLive?.stats?.series ?? []
  const clientsPerSecond = live?.clients?.requestedPerSecond ?? seenLive?.clients?.requestedPerSecond ?? null
  const chaos = report?.chaos ?? live?.chaos ?? seenLive?.chaos ?? []

  return (
    <main>
      <Hero />
      <div className="probe">
        probe:{' '}
        <label>
          <input type="checkbox" checked={smooth} onChange={(e) => setSmooth(e.target.checked)} /> smooth counters
        </label>
        {live && <span> · read {new Date(live.readAt).toLocaleTimeString()}</span>}
        {run?.booksFinalAt && run.finishedAt && (
          <span> · reconciling took {((Date.parse(run.finishedAt) - Date.parse(run.booksFinalAt)) / 1000).toFixed(1)} s</span>
        )}
      </div>
      <Controls
        sessionId={sessionId}
        session={session}
        selected={run}
        onCrashed={(at) => setPressedCrashAt(Date.parse(at))}
        onChanged={(started) => {
          if (started) setSelectedId(null)
          void loadRuns()
          void loadSession()
        }}
      />
      {connectionLost && (
        <p role="status" className="banner">
          Connection lost, retrying. The numbers below are the last ones read.
        </p>
      )}
      <section className="card">
        {elsewhere && (
          <button type="button" className="goto" onClick={() => setSelectedId(elsewhere.runId)}>
            ▶ A run is in progress ({elsewhere.tps}/s × {elsewhere.durationSeconds} s, {phaseName(elsewhere)}) — show it
          </button>
        )}
        <Phases run={run} />
        <Diagram numbers={numbers} pods={final ? undefined : run ? (live?.pods ?? null) : undefined} rates={final ? undefined : rates} />
        {(counters || report) && (
          <div className="money">
            <span>
              Moved <b>{report ? euros(report.ledger.debitsCents) : shown(counters?.movedCents, euros)}</b>
            </span>
            {report && (
              <>
                <span className={report.ledger.debitsCents === report.ledger.creditsCents ? 'eq' : 'bad'}>
                  debits {euros(report.ledger.debitsCents)} = credits {euros(report.ledger.creditsCents)}
                </span>
                <span>
                  Total in all accounts <b>{euros(report.ledger.balanceTotalCents)}</b>{' '}
                  {report.ledger.balanceTotalCents === report.ledger.openingTotalCents ? (
                    <span className="ok">unchanged</span>
                  ) : (
                    <span className="bad">was {euros(report.ledger.openingTotalCents)}</span>
                  )}
                </span>
              </>
            )}
          </div>
        )}
      </section>
      {run && (
        <Charts
          startedAt={run.startedAt}
          durationSeconds={run.durationSeconds}
          series={series}
          seriesAt={report?.stats?.at ?? live?.stats?.at ?? seenLive?.stats?.at ?? null}
          sending={run.state === 'RUNNING'}
          clientsPerSecond={clientsPerSecond}
          chaos={chaos}
          rateLines={rateLines}
          onRateLines={setRateLines}
        />
      )}
      {run?.failure && (
        <section className="card verdict">
          <div className="mark bad">✗</div>
          <h2>The run failed without a verdict</h2>
          <p>{run.failure}</p>
        </section>
      )}
      {report && run && <Verdict report={report} tps={run.tps} durationSeconds={run.durationSeconds} />}
      <RunList runs={runs} selectedId={selected?.runId ?? null} onSelect={setSelectedId} />
      <BuildInfo />
    </main>
  )
}
