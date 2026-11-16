import { useEffect, useRef, useState } from 'react'
import { api, ApiFailure, type SessionView } from './api/client'
import { clock, count, MS_PER_SECOND } from './format'
import { nextReadyAt, waitShown } from './readyCountdown'
import { t } from './texts'

const TOO_MANY = 429
const FORBIDDEN = 403
const NOT_IMPLEMENTED = 501
const UNAVAILABLE = 503
const LOAD_RUNNING = 'load-running'
const LOAD_USED_RECENTLY = 'load-used-recently'
const INVALID_LOAD = 'invalid-load'
const GENERATOR_DID_NOT_ANSWER = 'generator-did-not-answer'
/** Retry-After missing from a 429: the shortest wait the page can name. */
const UNKNOWN_WAIT_SECONDS = 1

function useNow() {
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), MS_PER_SECOND)
    return () => clearInterval(timer)
  }, [])
  return now
}

/** A button's wait: the server's seconds from the session, and a refusal's Retry-After, after which the session is read again. */
function useWait(serverSeconds: number, now: number, readAgain: () => void) {
  const [ready, setReady] = useState(() => {
    const at = Date.now()
    return { at, readyAt: nextReadyAt(0, serverSeconds, at) }
  })
  const [refusal, setRefusal] = useState({ at: 0, until: 0 })
  const latestReadAgain = useRef(readAgain)
  latestReadAgain.current = readAgain
  useEffect(() => {
    const at = Date.now()
    setReady((last) => ({ at, readyAt: nextReadyAt(last.readyAt, serverSeconds, at) }))
  }, [serverSeconds])
  useEffect(() => {
    if (refusal.until === 0) return
    const timer = setTimeout(() => latestReadAgain.current(), Math.max(0, refusal.until - Date.now()))
    return () => clearTimeout(timer)
  }, [refusal])
  return {
    // The clock ticks once a second, so it may be older than the last deadline; counted from it, 2 s would show as 3.
    seconds: waitShown(serverSeconds, ready.readyAt, refusal.until, Math.max(now, ready.at, refusal.at)),
    refused: (retryAfterSeconds: number) => {
      const at = Date.now()
      setRefusal({ at, until: at + retryAfterSeconds * MS_PER_SECOND })
    },
  }
}

function loadRefusal(e: unknown): string {
  if (!(e instanceof ApiFailure)) return t.controls.controlDidNotAnswer
  const wait = e.retryAfterSeconds ?? UNKNOWN_WAIT_SECONDS
  if (e.status === FORBIDDEN) return t.controls.inviteNotValid
  if (e.error === LOAD_RUNNING) return t.controls.load[LOAD_RUNNING](wait)
  if (e.error === LOAD_USED_RECENTLY) return t.controls.load[LOAD_USED_RECENTLY](wait)
  if (e.error === INVALID_LOAD) return t.controls.load[INVALID_LOAD]
  if (e.error === GENERATOR_DID_NOT_ANSWER) return t.controls.load[GENERATOR_DID_NOT_ANSWER]
  return t.controls.load.other(e.status)
}

function chaosRefusal(e: unknown): string {
  if (!(e instanceof ApiFailure)) return t.controls.controlDidNotAnswer
  if (e.status === FORBIDDEN) return t.controls.inviteNotValid
  if (e.status === TOO_MANY) return t.controls.chaos.usedRecently(e.retryAfterSeconds ?? UNKNOWN_WAIT_SECONDS)
  if (e.status === NOT_IMPLEMENTED) return t.controls.chaos.off
  if (e.status === UNAVAILABLE) return t.controls.chaos.noLedgerAnswered
  return t.controls.chaos.other(e.status)
}

export function Controls(props: { sessionId: string; session: SessionView; onChanged: () => void; onCrashed: (at: string) => void }) {
  const { session } = props
  const [tps, setTps] = useState(session.defaultLoadTps)
  const [seconds, setSeconds] = useState(session.defaultLoadSeconds)
  const [raising, setRaising] = useState(false)
  const [crashing, setCrashing] = useState(false)
  const [message, setMessage] = useState<string | null>(null)
  const now = useNow()
  const loadWait = useWait(session.loadReadyInSeconds, now, props.onChanged)
  const chaosWait = useWait(session.chaosReadyInSeconds, now, props.onChanged)

  const raise = async () => {
    setRaising(true)
    try {
      const pulse = await api.load(props.sessionId, { tps, seconds })
      setMessage(t.controls.raised(count(pulse.tps), clock(pulse.until)))
    } catch (e) {
      if (e instanceof ApiFailure && e.status === TOO_MANY && e.retryAfterSeconds !== null) loadWait.refused(e.retryAfterSeconds)
      setMessage(loadRefusal(e))
    } finally {
      setRaising(false)
    }
    props.onChanged()
  }

  const crash = async () => {
    setCrashing(true)
    try {
      const crashed = await api.chaos(props.sessionId)
      props.onCrashed(crashed.at)
      setMessage(t.controls.crashed(crashed.instances.length))
    } catch (e) {
      if (e instanceof ApiFailure && e.status === TOO_MANY && e.retryAfterSeconds !== null) chaosWait.refused(e.retryAfterSeconds)
      setMessage(chaosRefusal(e))
    } finally {
      setCrashing(false)
    }
    props.onChanged()
  }


  return (
    <div className="controlsBox">
      <div className="controls">
        <select aria-label={t.controls.tpsLabel} value={tps} onChange={(e) => setTps(Number(e.target.value))}>
          {session.loadTpsOptions.map((option) => (
            <option key={option} value={option}>
              {t.controls.tpsOption(count(option))}
            </option>
          ))}
        </select>
        <select aria-label={t.controls.secondsLabel} value={seconds} onChange={(e) => setSeconds(Number(e.target.value))}>
          {session.loadDurationOptions.map((option) => (
            <option key={option} value={option}>
              {t.controls.secondsOption(option)}
            </option>
          ))}
        </select>
        <button type="button" className="primary" disabled={!session.inviteValid || raising || loadWait.seconds !== null} onClick={raise}>
          {t.controls.raise}
          {loadWait.seconds !== null && <small className="why chaosWait">{t.controls.availableIn(loadWait.seconds)}</small>}
        </button>
        {session.chaosAvailable && (
          <button type="button" className="danger" disabled={!session.inviteValid || crashing || chaosWait.seconds !== null} onClick={crash}>
            {t.controls.crash}
            {chaosWait.seconds !== null && <small className="why chaosWait">{t.controls.availableIn(chaosWait.seconds)}</small>}
          </button>
        )}
      </div>
      {message && (
        <p role="alert" className="hint">
          {message}
        </p>
      )}
    </div>
  )
}
