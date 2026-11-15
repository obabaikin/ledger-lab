import { useCallback, useState } from 'react'
import { useParams } from 'react-router'
import { api, ApiFailure, type SessionView } from './api/client'
import { BankPage } from './BankPage'
import { Controls } from './Controls'
import { InviteForm } from './InviteForm'
import { t } from './texts'
import { usePolling } from './usePolling'

const NOT_FOUND = 404
const COUNTDOWN_POLL_MS = 1000
const SESSION_POLL_MS = 10000

export function SessionPage() {
  const { sessionId = '' } = useParams()
  const [session, setSession] = useState<SessionView | null>(null)
  const [missing, setMissing] = useState(false)
  const [lost, setLost] = useState(false)
  const [pressedCrashAt, setPressedCrashAt] = useState<number | null>(null)

  const poll = useCallback(async () => {
    try {
      setSession(await api.session(sessionId))
      setLost(false)
    } catch (e) {
      if (e instanceof ApiFailure && e.status === NOT_FOUND) setMissing(true)
      else setLost(true)
    }
  }, [sessionId])

  const counting = session !== null && (session.loadReadyInSeconds > 0 || session.chaosReadyInSeconds > 0)
  const pollNow = usePolling(poll, counting || lost ? COUNTDOWN_POLL_MS : SESSION_POLL_MS, !missing)

  if (missing) {
    return (
      <BankPage
        top={
          <section className="card">
            <p role="alert" className="hint">
              {t.sessionEnded.message}
            </p>
            <InviteForm />
          </section>
        }
      />
    )
  }

  return (
    <BankPage
      sessionLost={lost}
      pressedCrashAt={pressedCrashAt}
      controls={
        session && (
          <Controls
            sessionId={sessionId}
            session={session}
            onCrashed={(at) => setPressedCrashAt(Date.parse(at))}
            onChanged={() => void pollNow()}
          />
        )
      }
      after={
        session !== null &&
        !session.inviteValid && (
          <section className="card">
            <InviteForm />
          </section>
        )
      }
    />
  )
}
