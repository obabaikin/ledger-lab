import { useState } from 'react'
import { Link, useSearchParams } from 'react-router'
import { api, ApiFailure } from './api/client'
import { BuildInfo } from './BuildInfo'
import { Hero } from './Hero'
import { ControlError } from './api/errors'
import { ErrorText } from './InviteForm'
import { t } from './texts'

/** The server's limit window (control.invites.email.window); the texts name it without asking the server. */
const DELETION_DELAY_HOURS = 24
const text = t.deletePage

type Outcome = 'asked' | 'askedBefore' | 'unknown' | 'failed'

const STATUS_TEXT: Record<Exclude<Outcome, 'failed'>, string> = {
  asked: text.asked(DELETION_DELAY_HOURS),
  askedBefore: text.askedBefore(DELETION_DELAY_HOURS),
  unknown: text.unknown,
}

export function DeletePage() {
  const [params] = useSearchParams()
  const token = params.get('ref')?.trim() || null
  const [sending, setSending] = useState(false)
  const [outcome, setOutcome] = useState<Outcome | null>(token ? null : 'unknown')

  async function remove(ref: string) {
    setSending(true)
    try {
      const answer = await api.deleteAddress(ref)
      setOutcome(answer.alreadyAsked ? 'askedBefore' : 'asked')
    } catch (e) {
      setOutcome(e instanceof ApiFailure && e.error === ControlError.UNKNOWN_LINK ? 'unknown' : 'failed')
    } finally {
      setSending(false)
    }
  }

  return (
    <main>
      <Hero />
      <section className="card legal">
        <h2>{text.title}</h2>
        {outcome !== null && outcome !== 'failed' && <p role="status">{STATUS_TEXT[outcome]}</p>}
        {token && (outcome === null || outcome === 'failed') && (
          <>
            <p>{text.explanation}</p>
            <button type="button" className="primary" disabled={sending} onClick={() => remove(token)}>
              {text.button}
            </button>
          </>
        )}
        {outcome === 'failed' && <ErrorText>{text.failed}</ErrorText>}
        <p>
          <Link to="/privacy">{text.privacyLink}</Link> · <Link to="/">{text.backToStart}</Link>
        </p>
      </section>
      <BuildInfo />
    </main>
  )
}
