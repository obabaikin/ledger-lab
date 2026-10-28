import { act, fireEvent, screen, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import type { SiteView } from './api/client'
import { looksLikeEmail, waitText } from './InviteForm'
import { en } from './texts/en'
import { fakeControl, type Answer } from './test/fakeControl'
import { renderAt } from './test/render'

const REQUEST = 'POST /api/invite-requests'
const { checkUnavailable: CHECK_UNAVAILABLE, couldNotSend: COULD_NOT_SEND, notAnAddress: NOT_AN_ADDRESS, verificationFailed: VERIFICATION_FAILED } =
  en.inviteForm

const site = (changes: Partial<SiteView> = {}): SiteView => ({
  turnstileSiteKey: null,
  linkDays: 7,
  retentionDays: 30,
  linksPerDay: 3,
  operator: null,
  ...changes,
})

function ask(email: string) {
  fireEvent.change(screen.getByLabelText(en.inviteForm.emailLabel), { target: { value: email } })
  fireEvent.click(screen.getByRole('button', { name: en.inviteForm.submit }))
}

describe('the link form', () => {
  it('sends the address and says the same for any address once it is taken', async () => {
    const calls = fakeControl({ 'GET /api/site': { status: 200, body: site() }, [REQUEST]: { status: 202, body: {} } })
    renderAt('/')
    await screen.findByText(/The link works for 7 days\. Your address is deleted after 30 days without use\./)

    ask('visitor@example.org')

    expect(await screen.findByRole('status')).toHaveTextContent('A link is on its way to your inbox. You can ask up to 3 times a day.')
    expect(calls.find((c) => c.method === 'POST')?.body).toEqual({ email: 'visitor@example.org', turnstileToken: null })
    expect(screen.queryByRole('button', { name: en.inviteForm.submit })).not.toBeInTheDocument()
  })

  it.each<[string, Answer | (() => Answer), string]>([
    ['400', { status: 400, body: { error: 'invalid-email' } }, NOT_AN_ADDRESS],
    ['403', { status: 403, body: { error: 'verification-failed' } }, VERIFICATION_FAILED],
    ['429', { status: 429, body: { error: 'daily-limit' }, headers: { 'Retry-After': '7200' } }, 'Too many requests today; try again in 2 h.'],
    ['503', { status: 503, body: { error: 'busy' } }, COULD_NOT_SEND],
    ['400 with another code', { status: 400, body: { error: 'invalid request body' } }, COULD_NOT_SEND],
    ['code it does not know', { status: 422, body: { error: 'something-new' } }, COULD_NOT_SEND],
    ['no answer', () => { throw new TypeError('Failed to fetch') }, COULD_NOT_SEND],
  ])('names a %s in its own words and lets the visitor try again', async (_, answer, message) => {
    fakeControl({ 'GET /api/site': { status: 200, body: site() }, [REQUEST]: answer })
    renderAt('/')

    ask('visitor@example.org')

    expect(await screen.findByRole('alert')).toHaveTextContent(message)
    expect(screen.getByRole('button', { name: en.inviteForm.submit })).toBeEnabled()
  })

  it('says a malformed address is no address without asking control', async () => {
    const calls = fakeControl({ 'GET /api/site': { status: 200, body: site() }, [REQUEST]: { status: 202, body: {} } })
    renderAt('/')

    ask('with space@example.org')

    expect(await screen.findByRole('alert')).toHaveTextContent(NOT_AN_ADDRESS)
    expect(calls.some((c) => c.method === 'POST')).toBe(false)
  })

  it.each<[string, () => void]>([
    ['in the browser', () => ask('')],
    ['by control', () => ask('visitor@example.org')],
  ])('marks the field when the address is refused %s, puts the cursor back in it and clears the mark once it looks right', async (_, refuse) => {
    fakeControl({ 'GET /api/site': { status: 200, body: site() }, [REQUEST]: { status: 400, body: { error: 'invalid-email' } } })
    renderAt('/')
    const field = screen.getByLabelText(en.inviteForm.emailLabel)

    refuse()

    const alert = await screen.findByRole('alert')
    await waitFor(() => expect(field).toHaveAttribute('aria-invalid', 'true'))
    expect(field).toHaveAccessibleDescription(NOT_AN_ADDRESS)
    expect(field).toHaveFocus()
    fireEvent.change(field, { target: { value: 'visitor@example' } })
    expect(alert).not.toBeInTheDocument()
    expect(field).toHaveAttribute('aria-invalid', 'false')
  })

  it.each<[string, Answer]>([
    ['busy', { status: 503, body: { error: 'busy' } }],
    ['a 400 with another code', { status: 400, body: { error: 'invalid request body' } }],
  ])('leaves the field unmarked when the refusal is not about the address: %s', async (_, answer) => {
    fakeControl({ 'GET /api/site': { status: 200, body: site() }, [REQUEST]: answer })
    renderAt('/')

    ask('visitor@example.org')

    expect(await screen.findByRole('alert')).toHaveTextContent(COULD_NOT_SEND)
    expect(screen.getByLabelText(en.inviteForm.emailLabel)).toHaveAttribute('aria-invalid', 'false')
  })

  it('checks the same shape as the server', () => {
    ;['', 'a', '@b.c', 'a@', 'a@@b.c', 'a@b@c', 'a b@c.d', 'a,b@c.d', 'a@b\nc.d', 'a\u00a0b@c.d', 'a\u0007@b.c', 'a'.repeat(250) + '@b.cd']
      .forEach((email) => expect(looksLikeEmail(email), email).toBe(false))
    expect(looksLikeEmail('  First.Last+tag@sub.example.org ')).toBe(true)
  })

  it('holds the button while the request is on its way', async () => {
    let answer: (value: Response) => void = () => {}
    fakeControl({ 'GET /api/site': { status: 200, body: site() } })
    const pending = new Promise<Response>((resolve) => (answer = resolve))
    const original = globalThis.fetch
    globalThis.fetch = (input, init) => (String(input) === '/api/invite-requests' ? pending : original(input, init))
    try {
      renderAt('/')
      ask('visitor@example.org')

      await waitFor(() => expect(screen.getByRole('button', { name: en.inviteForm.submit })).toBeDisabled())
      answer(new Response('{}', { status: 202, headers: { 'Content-Type': 'application/json' } }))
      expect(await screen.findByRole('status')).toBeInTheDocument()
    } finally {
      globalThis.fetch = original
    }
  })

  it('says how long to wait in minutes under two hours, in whole hours above', () => {
    expect(waitText(59)).toBe('1 min')
    expect(waitText(3540)).toBe('59 min')
    expect(waitText(3601)).toBe('61 min')
    expect(waitText(7200)).toBe('2 h')
    expect(waitText(7201)).toBe('3 h')
  })
})

describe('the link form with the bot check on', () => {
  it('holds the button until the check answers, then sends its answer, and asks for a fresh one after an error', async () => {
    const callbacks: ((token: string) => void)[] = []
    vi.stubGlobal('turnstile', {
      render: (_: HTMLElement, options: { callback: (token: string) => void }) => {
        callbacks.push(options.callback)
        return `widget-${callbacks.length}`
      },
      reset: () => {},
      remove: () => {},
    })
    const calls = fakeControl({
      'GET /api/site': { status: 200, body: site({ turnstileSiteKey: 'site-key' }) },
      [REQUEST]: { status: 403, body: { error: 'verification-failed' } },
    })
    try {
      renderAt('/')
      await waitFor(() => expect(callbacks.length).toBeGreaterThan(0))
      fireEvent.change(screen.getByLabelText(en.inviteForm.emailLabel), { target: { value: 'visitor@example.org' } })
      expect(screen.getByRole('button', { name: en.inviteForm.submit })).toBeDisabled()

      act(() => callbacks.at(-1)!('first-answer'))
      fireEvent.click(screen.getByRole('button', { name: en.inviteForm.submit }))

      expect(await screen.findByRole('alert')).toHaveTextContent(VERIFICATION_FAILED)
      expect(calls.find((c) => c.method === 'POST')?.body).toEqual({ email: 'visitor@example.org', turnstileToken: 'first-answer' })
      await waitFor(() => expect(screen.getByRole('button', { name: en.inviteForm.submit })).toBeDisabled())
    } finally {
      vi.unstubAllGlobals()
    }
  })

  it('says so when the check cannot load', async () => {
    fakeControl({ 'GET /api/site': { status: 200, body: site({ turnstileSiteKey: 'site-key' }) } })
    renderAt('/')
    await waitFor(() => expect(document.head.querySelector('script[src*="challenges.cloudflare.com"]')).not.toBeNull())

    act(() => document.head.querySelector('script[src*="challenges.cloudflare.com"]')!.dispatchEvent(new Event('error')))

    expect(await screen.findByRole('alert')).toHaveTextContent(CHECK_UNAVAILABLE)
    expect(screen.getByRole('button', { name: en.inviteForm.submit })).toBeDisabled()
  })
})

describe('privacy and imprint', () => {
  it('show the operator the deployment sets', async () => {
    const operator = { name: 'Site Operator', address: 'Example Street 1, 12345 Example City', email: 'contact@example.org' }
    fakeControl({ 'GET /api/site': { status: 200, body: site({ operator }) } })
    renderAt('/impressum')

    expect(await screen.findByText(/Site Operator/)).toHaveTextContent('Example Street 1, 12345 Example City')
    expect(screen.getByText(en.impressum.responsible)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'contact@example.org' })).toHaveAttribute('href', 'mailto:contact@example.org')
  })

  it('privacy shows its seven sections in order, then the operator, who is not set where the deployment has none', async () => {
    fakeControl({ 'GET /api/site': { status: 200, body: site() } })
    renderAt('/privacy')

    const operator = (await screen.findAllByText(en.operator.notSet, { exact: false }))[0]!.closest('address')!
    const p = en.privacy
    const sections = [
      [p.heading1, p.text1], [p.heading2, p.text2], [p.heading3, p.text3], [p.heading4, p.text4],
      [p.heading5, p.text5], [p.heading6, p.text6], [p.heading7, p.text7],
    ]
    expect(screen.getByRole('heading', { level: 2 })).toHaveTextContent(p.title)
    expect(screen.getAllByRole('heading', { level: 3 }).map((h) => [h.textContent, h.nextElementSibling?.textContent])).toEqual(sections)
    expect(screen.getByRole('heading', { name: p.heading7 }).nextElementSibling?.nextElementSibling).toBe(operator)
  })
})
