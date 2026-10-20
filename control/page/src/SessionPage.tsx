import { useCallback, useMemo, useRef, useState } from 'react'
import { useParams } from 'react-router'
import { api, ApiFailure, FINAL_STATES, type LiveView, type RunReport, type RunView, type SessionView } from './api/client'
import { Charts } from './Charts'
import { Controls } from './Controls'
import { Diagram, NO_NUMBERS, PodLegend, type DiagramNumbers } from './Diagram'
import { flowRates, stopLedgerWires, type Flow } from './FlowDots'
import { euros, MS_PER_SECOND, shown } from './format'
import { BuildInfo } from './BuildInfo'
import { Hero } from './Hero'
import { InviteHint } from './InviteHint'
import { BANK_FINISHING_PHASE, booksFinal, PHASES, phaseOf, phaseName, VERDICT_PHASE } from './phase'
import { otherRunInProgress, runToShow } from './selectRun'
import { liveCounters } from './shownCounts'
import { useSmoothed } from './smooth'
import { usePolling } from './usePolling'
import { Verdict } from './Verdict'

const POLL_INTERVAL_MS = 1000
export const RUNS_LISTED = 10

/** What the page keeps of a run's live view after it ends: the report has no per-second series of the clients. */
type Seen = { runId: string; live: LiveView }
type Report = { runId: string; report: RunReport }

function Phases({ run }: { run: RunView | null }) {
  const now = run === null ? -1 : phaseOf(run)
  return (
    <div className="phases" aria-label="Phases">
      {PHASES.map((p, k) => (
        <span
          key={p}
          className={`phase ${k < now || (k === now && k === VERDICT_PHASE) ? 'done' : k === now ? 'now' : ''}`}
          aria-current={k === now ? 'step' : undefined}
        >
          {k < now ? '✓ ' : ''}
          {p}
          {k === 0 && run?.state === 'QUEUED' && run.position !== null ? ` · #${run.position} in line` : ''}
          {k === BANK_FINISHING_PHASE && run !== null && booksFinal(run) ? ' · books final ✓' : ''}
        </span>
      ))}
    </div>
  )
}

function RunList({ runs, selectedId, onSelect }: { runs: RunView[]; selectedId: string | null; onSelect: (id: string) => void }) {
  const [all, setAll] = useState(false)
  if (runs.length === 0) return null
  const listed = all ? runs : runs.slice(0, RUNS_LISTED)
  return (
    <section className="card runList">
      <h3>Runs of this session</h3>
      <div className="table-wrap">
        <table>
          <tbody>
            {listed.map((r) => (
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
      {!all && runs.length > RUNS_LISTED && (
        <button type="button" className="link" onClick={() => setAll(true)}>
          show all ({runs.length})
        </button>
      )}
    </section>
  )
}

function Expired({ run }: { run: RunView }) {
  return (
    <p className="banner" data-testid="expired">
      Expired: {run.failure ?? 'the run waited in the queue too long and did not start.'}
    </p>
  )
}

export function SessionPage() {
  const { sessionId = '' } = useParams()
  const [session, setSession] = useState<SessionView | null>(null)
  const [missing, setMissing] = useState(false)
  const [runs, setRuns] = useState<RunView[]>([])
  const [pickedId, setPickedId] = useState<string | null>(null)
  const picked = useRef<string | null>(null)
  picked.current = pickedId
  const [current, setCurrent] = useState<RunView | null>(null)
  const [seen, setSeen] = useState<Seen | null>(null)
  const [report, setReport] = useState<Report | null>(null)
  const reportRef = useRef<Report | null>(null)
  reportRef.current = report
  const [connectionLost, setConnectionLost] = useState(false)

  const poll = useCallback(async () => {
    try {
      const found = await api.session(sessionId).catch((e: unknown) => {
        if (e instanceof ApiFailure && e.status === 404) return null
        throw e
      })
      if (found === null) return setMissing(true)
      setSession(found)
      const { items } = await api.runs(sessionId)
      setRuns(items)
      const shownRun = runToShow(items, picked.current)
      if (shownRun !== null) {
        const view = FINAL_STATES.has(shownRun.state) ? shownRun : await api.run(sessionId, shownRun.runId)
        setCurrent(view)
        if (view.live) setSeen({ runId: view.runId, live: view.live })
        if (view.state === 'RECONCILED' && reportRef.current?.runId !== view.runId) {
          // 409 until the verdict is stored: the next poll asks again.
          const got = await api.report(sessionId, view.runId).catch((e: unknown) => {
            if (e instanceof ApiFailure) return null
            throw e
          })
          if (got) setReport({ runId: view.runId, report: got })
        }
      }
      setConnectionLost(false)
    } catch (e) {
      if (!(e instanceof ApiFailure)) setConnectionLost(true)
    }
  }, [sessionId])

  const selected = runToShow(runs, pickedId)
  const elsewhere = otherRunInProgress(runs, selected)
  const run = current?.runId === selected?.runId ? current : selected
  const final = run !== null && FINAL_STATES.has(run.state)
  const runReport = report !== null && report.runId === run?.runId ? report.report : null
  const unfinished = runs.some((r) => !FINAL_STATES.has(r.state))
  const waitingForReport = run?.state === 'RECONCILED' && runReport === null
  const chaosCountingDown = (session?.chaosReadyInSeconds ?? 0) > 0
  const pollNow = usePolling(poll, POLL_INTERVAL_MS, !missing && (session === null || connectionLost || unfinished || waitingForReport || chaosCountingDown))

  const live = run?.live ?? null
  const seenLive = seen !== null && seen.runId === run?.runId ? seen.live : null
  const target = useMemo(() => liveCounters(live), [live])
  const counters = useSmoothed(target, POLL_INTERVAL_MS)
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
    return { rates: flowRates(prev?.value ?? null, value, prev ? (at - prev.at) / MS_PER_SECOND : 0), from: prev?.at ?? null }
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
        <section className="card">
          <div className="controls">
            <span role="alert">This session has ended or the link is wrong. Open your invite link again to start a new one. </span>
            <InviteHint />
          </div>
        </section>
      </main>
    )
  }
  if (session === null) {
    return (
      <main aria-busy="true">
        {connectionLost && (
          <p role="status" className="banner">
            Connection lost, retrying.
          </p>
        )}
      </main>
    )
  }

  const numbers: DiagramNumbers = runReport
    ? {
        requested: runReport.client?.requested ?? null,
        accepted: runReport.gateway.accepted,
        posted: runReport.ledger.posted,
        rejected: runReport.ledger.rejected,
        ledgerDuplicatesAtLeast: runReport.ledgerDuplicatesAtLeast,
        duplicatesSent: seenLive?.clients?.duplicatesSent ?? null,
        refused: seenLive?.clients?.refused ?? null,
      }
    : counters && live
      ? {
          requested: counters.requested,
          accepted: counters.accepted,
          posted: counters.posted,
          rejected: counters.rejected,
          ledgerDuplicatesAtLeast: live.ledgerDuplicatesAtLeast,
          duplicatesSent: live.clients?.duplicatesSent ?? null,
          refused: live.clients?.refused ?? null,
        }
      : NO_NUMBERS
  const series = runReport?.stats?.series ?? live?.stats?.series ?? seenLive?.stats?.series ?? []
  const clientsPerSecond = live?.clients?.requestedPerSecond ?? seenLive?.clients?.requestedPerSecond ?? null
  const chaos = runReport?.chaos ?? live?.chaos ?? seenLive?.chaos ?? []
  const pods = final || run === null ? undefined : (live?.pods ?? null)

  const scheme = (
    <>
      {run?.state === 'EXPIRED' && <Expired run={run} />}
      <Diagram numbers={numbers} pods={pods} rates={final ? undefined : rates} />
      <PodLegend />
      {(counters || runReport) && (
        <div className="money">
          <span>
            Moved <b>{runReport ? euros(runReport.ledger.debitsCents) : shown(counters?.movedCents, euros)}</b>
          </span>
          {runReport && (
            <>
              <span className={runReport.ledger.debitsCents === runReport.ledger.creditsCents ? 'eq' : 'bad'}>
                debits {euros(runReport.ledger.debitsCents)} = credits {euros(runReport.ledger.creditsCents)}
              </span>
              <span>
                Total in all accounts <b>{euros(runReport.ledger.balanceTotalCents)}</b>{' '}
                {runReport.ledger.balanceTotalCents === runReport.ledger.openingTotalCents ? (
                  <span className="ok">unchanged</span>
                ) : (
                  <span className="bad">was {euros(runReport.ledger.openingTotalCents)}</span>
                )}
              </span>
            </>
          )}
        </div>
      )}
    </>
  )

  return (
    <main>
      <Hero />
      <Controls
        sessionId={sessionId}
        session={session}
        selected={run}
        unfinished={unfinished}
        onCrashed={(at) => setPressedCrashAt(Date.parse(at))}
        onChanged={(started) => {
          if (started) setPickedId(null)
          void pollNow()
        }}
      />
      {connectionLost && (
        <p role="status" className="banner">
          Connection lost, retrying. The numbers below are the last ones read.
        </p>
      )}
      <section className="card">
        {elsewhere && (
          <button type="button" className="goto" onClick={() => setPickedId(elsewhere.runId)}>
            ▶ A run is in progress ({elsewhere.tps}/s × {elsewhere.durationSeconds} s, {phaseName(elsewhere)}) — show it
          </button>
        )}
        <Phases run={run} />
        {scheme}
      </section>
      {run && (
        <Charts
          startedAt={run.startedAt}
          durationSeconds={run.durationSeconds}
          series={series}
          seriesAt={runReport?.stats?.at ?? live?.stats?.at ?? seenLive?.stats?.at ?? null}
          sending={run.state === 'RUNNING'}
          clientsPerSecond={clientsPerSecond}
          chaos={chaos}
        />
      )}
      {run?.state === 'FAILED' && (
        <section className="card verdict">
          <div className="mark bad">✗</div>
          <h2>The run failed without a verdict</h2>
          <p>{run.failure}</p>
        </section>
      )}
      {runReport && run && <Verdict report={runReport} tps={run.tps} durationSeconds={run.durationSeconds} />}
      <RunList runs={runs} selectedId={selected?.runId ?? null} onSelect={setPickedId} />
      <BuildInfo />
    </main>
  )
}
