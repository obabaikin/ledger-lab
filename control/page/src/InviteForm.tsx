import { useCallback, useRef, useState, type FormEvent } from 'react'
import { Link } from 'react-router'
import { api, ApiFailure } from './api/client'
import { TurnstileWidget } from './Turnstile'
import { ControlError } from './api/errors'
import { t } from './texts'
import { useSite } from './useSite'

const text = t.inviteForm

const EMAIL_ERROR_ID = 'email-error'
const CHECK_HINT_ID = 'check-hint'
const MAX_EMAIL_LENGTH = 254
/** The server's rule: one @ with something on each side, no whitespace, comma or control character. */
const EMAIL_SHAPE = /^[^@\s,\x00-\x1f\x7f-\x9f]+@[^@\s,\x00-\x1f\x7f-\x9f]+$/

export function looksLikeEmail(raw: string): boolean {
  const email = raw.trim()
  return email.length <= MAX_EMAIL_LENGTH && EMAIL_SHAPE.test(email)
}

const SECONDS_PER_MINUTE = 60
const MINUTES_PER_HOUR = 60
/** Below two hours in minutes: rounded up to hours, 61 minutes would read as 2 h. */
const MINUTES_SHOWN_BELOW = 2 * MINUTES_PER_HOUR

export function waitText(seconds: number): string {
  const minutes = Math.ceil(seconds / SECONDS_PER_MINUTE)
  return minutes < MINUTES_SHOWN_BELOW ? text.waitMinutes(minutes) : text.waitHours(Math.ceil(minutes / MINUTES_PER_HOUR))
}

function messageOf(e: unknown): string {
  if (!(e instanceof ApiFailure)) return text.couldNotSend
  if (e.error === ControlError.VERIFICATION_FAILED) return text.verificationFailed
  if (e.error === ControlError.DAILY_LIMIT && e.retryAfterSeconds !== null) return text.tooManyRequests(waitText(e.retryAfterSeconds))
  return text.couldNotSend
}

export function InviteForm() {
  const site = useSite()
  const [email, setEmail] = useState('')
  const [token, setToken] = useState<string | null>(null)
  const [sending, setSending] = useState(false)
  const [sent, setSent] = useState(false)
  const [message, setMessage] = useState<string | null>(null)
  const [emailInvalid, setEmailInvalid] = useState(false)
  const emailInput = useRef<HTMLInputElement>(null)
  const [widgetRound, setWidgetRound] = useState(0)
  const [checkUnavailable, setCheckUnavailable] = useState(false)
  const [checkWanted, setCheckWanted] = useState(false)
  const onToken = useCallback((answer: string | null) => {
    setToken(answer)
    if (answer !== null) setCheckUnavailable(false)
  }, [])
  const onUnavailable = useCallback(() => setCheckUnavailable(true), [])
  const waitingForCheck = Boolean(site?.turnstileSiteKey) && token === null
  const errorText = message ?? (checkUnavailable ? text.checkUnavailable : null)
  const checking = waitingForCheck && checkWanted && !checkUnavailable && errorText === null

  function rejectEmail() {
    setMessage(text.notAnAddress)
    setEmailInvalid(true)
    emailInput.current?.focus()
  }

  function wantCheck() {
    setCheckWanted(true)
    if (checkUnavailable) {
      setCheckUnavailable(false)
      setWidgetRound((round) => round + 1)
    }
  }

  function changeEmail(value: string) {
    setCheckWanted(true)
    setEmail(value)
    if (emailInvalid && looksLikeEmail(value)) {
      setMessage(null)
      setEmailInvalid(false)
    }
  }

  async function submit(event: FormEvent) {
    event.preventDefault()
    if (waitingForCheck) return
    if (!looksLikeEmail(email)) {
      rejectEmail()
      return
    }
    setSending(true)
    setMessage(null)
    setEmailInvalid(false)
    try {
      await api.requestInvite({ email, turnstileToken: token })
      setSent(true)
    } catch (e) {
      if (e instanceof ApiFailure && e.error === ControlError.INVALID_EMAIL) rejectEmail()
      else setMessage(messageOf(e))
      setWidgetRound((round) => round + 1)
    } finally {
      setSending(false)
    }
  }

  if (sent) {
    return (
      <p role="status" className="invite-sent">
        {text.sent} {text.deletionLink}
        {site && ` ${text.linksPerDay(site.linksPerDay)}`}
      </p>
    )
  }

  return (
    <form className="invite" onSubmit={submit} noValidate>
      <h2>{text.heading}</h2>
      <div className="controls">
        <label>
          {text.emailLabel}{' '}
          <input
            ref={emailInput}
            type="email"
            name="email"
            autoComplete="email"
            required
            value={email}
            aria-invalid={emailInvalid}
            aria-describedby={emailInvalid ? EMAIL_ERROR_ID : undefined}
            onFocus={wantCheck}
            onChange={(e) => changeEmail(e.target.value)}
          />
        </label>
        <button type="submit" className="primary" disabled={sending || waitingForCheck} aria-describedby={checking ? CHECK_HINT_ID : undefined}>
          {text.submit}
        </button>
      </div>
      <div className="form-status">
        {errorText !== null ? (
          <ErrorText id={EMAIL_ERROR_ID}>{errorText}</ErrorText>
        ) : (
          checking && (
            <p id={CHECK_HINT_ID} className="hint" role="status">
              {text.checking}
            </p>
          )
        )}
      </div>
      {site?.turnstileSiteKey && checkWanted && (
        <TurnstileWidget siteKey={site.turnstileSiteKey} onToken={onToken} onUnavailable={onUnavailable} resetKey={widgetRound} />
      )}
      <p className="hint">
        {site && `${text.terms(site.linkDays, site.retentionDays)} `}
        <Link to="/privacy">{text.privacyLink}</Link>
      </p>
    </form>
  )
}

export function ErrorText(props: { id?: string; children: string }) {
  return (
    <p role="alert" id={props.id} className="error">
      <span aria-hidden="true">⚠ </span>
      {props.children}
    </p>
  )
}
