import { fireEvent, screen, waitFor } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { fakeControl, type Answer } from './test/fakeControl'
import { renderAt } from './test/render'
import { en } from './texts/en'

const DELETION = 'POST /api/address-deletions'
const BUTTON = { name: en.deletePage.button }
const NO_ADDRESS = en.deletePage.unknown
const COULD_NOT_SEND = en.deletePage.failed

describe('the delete page', () => {
  it('sends nothing until the button is pressed, then the link token, and needs no site settings for its text', async () => {
    const calls = fakeControl({ [DELETION]: { status: 202, body: { deleteAt: '2026-10-27T10:00:00Z', alreadyAsked: false } } })
    renderAt('/delete?ref=mail-token')
    await screen.findByRole('button', BUTTON)

    expect(calls.some((c) => c.method === 'POST')).toBe(false)
    fireEvent.click(screen.getByRole('button', BUTTON))

    expect(await screen.findByRole('status')).toHaveTextContent(en.deletePage.asked(24))
    expect(calls.filter((c) => c.method === 'POST')).toEqual([{ method: 'POST', path: '/api/address-deletions', body: { token: 'mail-token' } }])
    expect(screen.queryByRole('button', BUTTON)).not.toBeInTheDocument()
  })

  it('counts from the first request when the deletion was already asked', async () => {
    fakeControl({ [DELETION]: { status: 202, body: { deleteAt: '2026-10-27T10:00:00Z', alreadyAsked: true } } })
    renderAt('/delete?ref=mail-token')

    fireEvent.click(await screen.findByRole('button', BUTTON))

    expect(await screen.findByRole('status')).toHaveTextContent(en.deletePage.askedBefore(24))
  })

  it.each<[string, Answer | (() => Answer), string, boolean]>([
    ['404', { status: 404, body: { error: 'unknown-link' } }, NO_ADDRESS, false],
    ['500', { status: 500, body: { error: 'Internal Server Error' } }, COULD_NOT_SEND, true],
    ['404 with another code', { status: 404, body: { error: 'Not Found' } }, COULD_NOT_SEND, true],
    ['code it does not know', { status: 422, body: { error: 'something-new' } }, COULD_NOT_SEND, true],
    ['no answer', () => { throw new TypeError('Failed to fetch') }, COULD_NOT_SEND, true],
  ])('names a %s in its own words', async (_, answer, message, retry) => {
    fakeControl({ [DELETION]: answer })
    renderAt('/delete?ref=mail-token')

    fireEvent.click(await screen.findByRole('button', BUTTON))

    expect(await screen.findByText(message)).toBeInTheDocument()
    expect(screen.queryByRole('button', BUTTON) !== null).toBe(retry)
  })

  it.each(['/delete', '/delete?ref=', '/delete?ref=%20'])('at %s shows no button and sends nothing', async (url) => {
    const calls = fakeControl({})
    renderAt(url)

    expect(await screen.findByRole('status')).toHaveTextContent(NO_ADDRESS)
    expect(screen.queryByRole('button', BUTTON)).not.toBeInTheDocument()
    expect(calls.some((c) => c.method === 'POST')).toBe(false)
  })

  it('holds the button while the request is on its way', async () => {
    let answer: (value: Response) => void = () => {}
    fakeControl({})
    const pending = new Promise<Response>((resolve) => (answer = resolve))
    const original = globalThis.fetch
    globalThis.fetch = (input, init) => (String(input) === '/api/address-deletions' ? pending : original(input, init))
    try {
      renderAt('/delete?ref=mail-token')
      fireEvent.click(await screen.findByRole('button', BUTTON))

      await waitFor(() => expect(screen.getByRole('button', BUTTON)).toBeDisabled())
      answer(new Response('{"deleteAt":"2026-10-27T10:00:00Z","alreadyAsked":false}', { status: 202, headers: { 'Content-Type': 'application/json' } }))
      expect(await screen.findByRole('status')).toBeInTheDocument()
    } finally {
      globalThis.fetch = original
    }
  })
})
