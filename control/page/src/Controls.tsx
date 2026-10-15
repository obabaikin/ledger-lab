import { useEffect, useRef, useState, type FormEvent } from 'react'
import { chaosWaitShown, nextChaosReadyAt } from './chaosReady'
import { api, ApiFailure, type RunView, type SessionView } from './api/client'

const SECONDS_PER_MINUTE = 60
const MS_PER_SECOND = 1000

function refusal(e: unknown): string {
  if (!(e instanceof ApiFailure)) return 'Control did not answer. Press Start again: the same run is not started twice.'
  if (e.status === 429 && e.retryAfterSeconds !== null) {
    return `The invite's runs for this hour are used up. Next run possible in ${Math.ceil(e.retryAfterSeconds / SECONDS_PER_MINUTE)} min.`
  }
  if (e.status === 429) return 'The queue is full. Try again in a few minutes.'
  if (e.status === 403) return 'The invite has expired. Runs of this session can still be read.'
  return `Not started: ${e.error}.`
}

function useNow() {
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), MS_PER_SECOND)
    return () => clearInterval(timer)
  }, [])
  return now
}

export function Controls(props: {
  sessionId: string
  session: SessionView
  selected: RunView | null
  onChanged: (run?: RunView) => void
  onCrashed: (at: string) => void
}) {
  const { session, selected } = props
  const [tps, setTps] = useState(session.tpsOptions.at(-1) ?? 0)
  const [durationSeconds, setDurationSeconds] = useState(session.durationOptions.at(-1) ?? 0)
  const [sending, setSending] = useState(false)
  const [message, setMessage] = useState<string | null>(null)
  const [chaosReadyAt, setChaosReadyAt] = useState(0)
  const [serverWaitSeconds, setServerWaitSeconds] = useState(session.chaosReadyInSeconds)
  // Kept until control answers, so a repeat after a lost answer is the same request, not a second run.
  const pendingRequestId = useRef<string | null>(null)
  const now = useNow()

  useEffect(() => {
    setChaosReadyAt((at) => nextChaosReadyAt(at, session.chaosReadyInSeconds, Date.now()))
    setServerWaitSeconds(session.chaosReadyInSeconds)
  }, [session])

  const choose = (change: () => void) => {
    pendingRequestId.current = null
    change()
  }

  const start = async (event: FormEvent) => {
    event.preventDefault()
    pendingRequestId.current ??= crypto.randomUUID()
    setSending(true)
    setMessage(null)
    try {
      const run = await api.startRun(props.sessionId, { requestId: pendingRequestId.current, tps, durationSeconds })
      pendingRequestId.current = null
      props.onChanged(run)
    } catch (e) {
      if (e instanceof ApiFailure) pendingRequestId.current = null
      setMessage(refusal(e))
    } finally {
      setSending(false)
    }
  }

  const stop = async () => {
    if (!selected) return
    try {
      props.onChanged(await api.stopRun(props.sessionId, selected.runId))
    } catch (e) {
      setMessage(e instanceof ApiFailure ? `Not stopped: ${e.error}.` : 'Control did not answer.')
    }
  }

  const crash = async () => {
    try {
      const crashed = await api.chaos(props.sessionId)
      props.onCrashed(crashed.at)
      setMessage(`Crashed ${crashed.instances.length} ledger instances. They restart; the run goes on.`)
    } catch (e) {
      if (e instanceof ApiFailure && e.retryAfterSeconds !== null) {
        setChaosReadyAt(Date.now() + e.retryAfterSeconds * MS_PER_SECOND)
        setServerWaitSeconds(e.retryAfterSeconds)
      }
      setMessage(e instanceof ApiFailure ? `No crash: ${e.error}.` : 'Control did not answer.')
    }
    props.onChanged()
  }

  const running = selected?.state === 'RUNNING'
  const chaosWaitSeconds = chaosWaitShown(serverWaitSeconds, chaosReadyAt, now)
  const chaosCountdown = chaosWaitSeconds === null ? null : `available in ${chaosWaitSeconds} s`

  return (
    <section className="card">
      <form className="controls" onSubmit={start}>
        <select aria-label="Transfers per second" value={tps} onChange={(e) => choose(() => setTps(Number(e.target.value)))}>
          {session.tpsOptions.map((option) => (
            <option key={option} value={option}>
              {option} transfers/s
            </option>
          ))}
        </select>
        <select aria-label="Duration" value={durationSeconds} onChange={(e) => choose(() => setDurationSeconds(Number(e.target.value)))}>
          {session.durationOptions.map((option) => (
            <option key={option} value={option}>
              {option} s
            </option>
          ))}
        </select>
        <button type="submit" className="primary" disabled={!session.inviteValid || sending}>
          Start the run
        </button>
        <button type="button" className="primary" disabled={!running || selected?.stopRequested} onClick={stop}>
          ■ Stop
        </button>
        {session.chaosAvailable &&
          (chaosCountdown === null ? (
            <button type="button" className="danger" disabled={!running} title={running ? undefined : 'While clients are sending'} onClick={crash}>
              ⚡ Crash the ledger (all 3 instances)
            </button>
          ) : (
            <span className="hint chaosWait">⚡ Crash the ledger: {chaosCountdown}</span>
          ))}
        <span className="pill">
          {session.inviteValid ? `Runs this hour: ${session.runsLastHour} of ${session.runsPerHour}` : 'Invite expired; runs can still be read'}
        </span>
      </form>
      {message && (
        <p role="alert" className="hint">
          {message}
        </p>
      )}
    </section>
  )
}
