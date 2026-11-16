import { cleanup, fireEvent, screen, waitFor, within } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { clock, count, dayAndMonth, hoursMinutes } from './format'
import { bank, fakeControl, period, PERIOD_START, report, series, SERIES, session, SESSION_ID, type Answer } from './test/fakeControl'
import { renderAt } from './test/render'
import { en } from './texts/en'

const SESSION = `/api/sessions/${SESSION_ID}`
const PERIODS = '/api/bank/periods?limit=10'
const PERIOD = `/api/bank/periods/${encodeURIComponent(PERIOD_START)}`

type Answers = Record<string, Answer | (() => Answer | Promise<Answer>)>

function control(extra: Answers = {}) {
  return fakeControl({
    'GET /api/bank': { status: 200, body: bank() },
    [`GET ${PERIODS}`]: { status: 200, body: { periods: [] } },
    [SERIES]: { status: 200, body: series() },
    [`GET ${SESSION}`]: { status: 200, body: session() },
    ...extra,
  })
}

const raise = () => screen.findByRole('button', { name: new RegExp(en.controls.raise) })
const crash = () => screen.findByRole('button', { name: /Crash the ledger/ })
const follows = (first: Element, second: Element) => (first.compareDocumentPosition(second) & Node.DOCUMENT_POSITION_FOLLOWING) !== 0

describe('the open page', () => {
  it('shows the bank to a visitor without a session, with no buttons and the line that offers a link above the bank', async () => {
    control()
    renderAt('/')

    expect(await screen.findByTestId('bank-status')).toHaveTextContent('The bank runs 24/7: since')
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('Ledger Lab')
    expect(screen.getByTestId('gateway-number')).toHaveTextContent('1,000 accepted')
    expect(screen.queryByRole('button', { name: new RegExp(en.controls.raise) })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Crash the ledger/ })).not.toBeInTheDocument()
    expect(follows(screen.getByRole('heading', { level: 2, name: en.inviteForm.heading }), screen.getByTestId('bank-status'))).toBe(true)
    expect(screen.getByRole('link', { name: 'Oleksii Babaikin' })).toHaveAttribute('href', expect.stringContaining('linkedin.com/in/'))
    expect(screen.getAllByRole('link', { name: en.footer.privacy })[0]).toHaveAttribute('href', '/privacy')
    expect(screen.getByRole('link', { name: en.footer.impressum })).toHaveAttribute('href', '/impressum')
    expect(screen.getByRole('link', { name: 'How it works' })).toHaveAttribute('href', expect.stringContaining('#what-it-does'))
  })

  it('opens the form for a link in place under the line at the top, and keeps no form below the bank', async () => {
    control()
    renderAt('/')
    await screen.findByTestId('bank-status')
    expect(screen.queryByLabelText(en.inviteForm.emailLabel)).not.toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: en.landing.tryIt }))

    const opened = screen.getByTestId('invite-opened')
    expect(opened).toHaveFocus()
    expect(screen.getByLabelText(en.inviteForm.emailLabel)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: en.landing.tryIt })).not.toBeInTheDocument()
    expect(screen.getAllByRole('heading', { level: 2, name: en.inviteForm.heading })).toHaveLength(1)
    expect(follows(opened, screen.getByTestId('bank-status'))).toBe(true)
  })

  it('shows how fast the clients send now, large, at the top', async () => {
    control()
    renderAt('/')

    expect(await screen.findByTestId('clients-speed')).toHaveTextContent(`${en.bank.speed('400')} ${en.bank.speedNow}`)
    expect(follows(screen.getByTestId('clients-speed'), screen.getByTestId('bank-status'))).toBe(true)
  })

  it('says the bank has not started before control placed its first period', async () => {
    control({ 'GET /api/bank': { status: 200, body: bank({ startedAt: null }) } })
    renderAt('/')

    expect(await screen.findByTestId('bank-status')).toHaveTextContent(en.bank.notStarted)
  })

  it('shows the books, the money moved and the rejections by reason since the bank started', async () => {
    control()
    renderAt('/')

    expect(await screen.findByTestId('books')).toHaveTextContent(`${en.bank.totalInAccounts} €1,000,000,000.00 ${en.bank.unchanged}`)
    expect(screen.getByText('100,000')).toBeInTheDocument()
    expect(screen.getByTestId('rejections')).toHaveTextContent(`${en.bank.rejected('5')}: insufficient funds 4, blocked account 1`)
    await waitFor(() => expect(screen.getByTestId('moved')).toHaveTextContent('€990.00'))
  })

  it('shows on the scheme what the clients sent and gave up on since the bank started, and below it their own counts since they restarted', async () => {
    control()
    renderAt('/')

    await waitFor(() => expect(screen.getByTestId('clients-number')).toHaveTextContent(en.bank.clientsSent('917,000')))
    expect(screen.getByTestId('clients-note')).toHaveTextContent(en.bank.clientsGaveUp('600'))
    expect(screen.getByTestId('clients-restarted')).toHaveTextContent(en.bank.clientsRestarted(hoursMinutes('2026-11-11T07:15:00Z'), '1,100', '3'))
  })

  it('shows no line of the clients\' own counts when no clients sent before them', async () => {
    control({ 'GET /api/bank': { status: 200, body: bank({ clients: { ...bank().clients!, restarted: false } }) } })
    renderAt('/')

    expect(await screen.findByTestId('clients-number')).toBeInTheDocument()
    expect(screen.queryByTestId('clients-restarted')).toBeNull()
  })

  it('shows every visitor a raised load and the last crash of the ledger', async () => {
    const until = '2026-11-11T10:30:20Z'
    const crashedAt = '2026-11-11T10:29:50Z'
    control({
      'GET /api/bank': {
        status: 200,
        body: bank({
          clients: { ...bank().clients!, targetTps: 1500, pulse: { tps: 1500, until } },
          chaos: { available: true, readyInSeconds: 60, recent: [{ at: crashedAt, instances: ['ledger-0', 'ledger-1'] }] },
        }),
      },
    })
    renderAt('/')

    expect(await screen.findByTestId('clients-speed')).toHaveTextContent(`${en.bank.speed('1,500')} ${en.bank.speedUntil(clock(until), '400')}`)
    expect(screen.getByTestId('last-crash')).toHaveTextContent(en.bank.lastCrash(clock(crashedAt), 2))
  })

  it('shows the error budget used, in the words of the page texts', async () => {
    control()
    renderAt('/')

    expect(await screen.findByTestId('budget')).toHaveTextContent(en.availability.budget(17, 43))
    expect(screen.getByRole('meter', { name: en.availability.budget(17, 43) })).toHaveAttribute('aria-valuenow', '17')
    expect(screen.getByTestId('budget-split')).toHaveTextContent(en.availability.split(17, 12, 2))
  })

  it('shows the time to posting as a row per threshold with its share, target and budget used, the transfers left out, and links to how it is measured', async () => {
    control()
    renderAt('/')

    expect(await screen.findByTestId('posting-0')).toHaveTextContent(en.availability.postingValue(3, '99.97 %', '95 %'))
    expect(screen.getByTestId('posting-0-budget')).toHaveTextContent(en.availability.postingBudget('1 %'))
    expect(screen.getByTestId('posting-1')).toHaveTextContent(en.availability.postingValue(5, '99.98 %', '99 %'))
    expect(screen.getByRole('meter', { name: en.availability.postingBudget('2 %') })).toHaveAttribute('aria-valuenow', '0.02')
    expect(screen.getByTestId('posting-split')).toHaveTextContent(en.availability.postingSplit('12,000', '3,400'))
    expect(screen.getByTestId('posting-window')).toHaveTextContent(en.availability.postingWindow(30))
    expect(screen.getByText(en.availability.postingTitle)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: en.availability.howMeasured })).toHaveAttribute(
      'href', 'https://github.com/obabaikin/ledger-lab/blob/main/docs/guides/slo.md')
  })

  it('says the time to posting is not measured before the first judged transfer, not 100 %, and since when it is counted', async () => {
    const since = '2026-11-17T03:00:00Z'
    const posting = bank().posting
    control({
      'GET /api/bank': {
        status: 200,
        body: bank({
          posting: {
            ...posting,
            since,
            judged: 0,
            targets: posting.targets.map((target) => ({ ...target, within: 0, share: null, budgetUsed: null })),
          },
        }),
      },
    })
    renderAt('/')

    expect(await screen.findByTestId('posting-0')).toHaveTextContent(en.availability.postingNotMeasured(3, '95 %'))
    expect(screen.getByTestId('posting-1')).toHaveTextContent(en.availability.postingNotMeasured(5, '99 %'))
    expect(screen.queryByTestId('posting-0-budget')).toBeNull()
    expect(screen.getByTestId('posting-window')).toHaveTextContent(en.availability.postingSince(dayAndMonth(since)))
  })

  it('shows the target missed when more transfers were slow than it allows', async () => {
    const posting = bank().posting
    const [typical, tail] = posting.targets
    control({
      'GET /api/bank': { status: 200, body: bank({ posting: { ...posting, targets: [{ ...typical!, share: 0.94, budgetUsed: 1.2 }, tail!] } }) },
    })
    renderAt('/')

    expect(await screen.findByTestId('posting-0-budget')).toHaveTextContent(en.availability.postingBudget('120 %'))
    expect(screen.getByRole('meter', { name: en.availability.postingBudget('120 %') })).toHaveAttribute('aria-valuenow', '1')
  })

  it('shows the base load and in how many minutes it changes, the base alone while it is constant, and nothing without an answer from the clients', async () => {
    const now = Date.parse('2026-11-11T10:30:30Z')
    vi.spyOn(Date, 'now').mockReturnValue(now)
    control({ 'GET /api/bank': { status: 200, body: bank({ load: { baseTps: 600, nextChangeAt: '2026-11-11T10:35:00Z' } }) } })
    renderAt('/')
    expect(await screen.findByTestId('base-load')).toHaveTextContent(en.bank.baseChangesIn('600', 5))
    cleanup()

    control({ 'GET /api/bank': { status: 200, body: bank({ load: { baseTps: 400, nextChangeAt: null } }) } })
    renderAt('/')
    expect(await screen.findByTestId('base-load')).toHaveTextContent(en.bank.base('400'))
    cleanup()

    control({ 'GET /api/bank': { status: 200, body: bank({ load: null }) } })
    renderAt('/')
    await screen.findByTestId('clients-speed')
    expect(screen.queryByTestId('base-load')).toBeNull()
  })

  it('shows the planned update in the visitor\'s time, and says when none is planned', async () => {
    const window = { from: '2026-11-12T02:00:00Z', to: '2026-11-12T02:30:00Z' }
    control({ 'GET /api/bank': { status: 200, body: bank({ maintenance: window }) } })
    renderAt('/')

    expect(await screen.findByTestId('maintenance')).toHaveTextContent(
      en.availability.update(dayAndMonth(window.from), hoursMinutes(window.from), hoursMinutes(window.to)),
    )
  })

  it('says no update is planned without a window', async () => {
    control()
    renderAt('/')

    expect(await screen.findByTestId('maintenance')).toHaveTextContent(en.availability.noUpdate)
  })

  it('keeps the last numbers and says the connection is lost when a poll gets a 502 from the proxy', async () => {
    let up = true
    control({ 'GET /api/bank': () => (up ? { status: 200, body: bank() } : { status: 502, body: { error: 'bad gateway' } }) })
    renderAt('/')
    await waitFor(() => expect(screen.getByTestId('gateway-number')).toHaveTextContent('1,000 accepted'))

    up = false

    expect(await screen.findByText(en.connection.lost, {}, { timeout: 3000 })).toBeInTheDocument()
    expect(screen.getByTestId('gateway-number')).toHaveTextContent('1,000 accepted')
  })

  it('says when the charts or the periods could not be read, instead of leaving them out', async () => {
    control({ [SERIES]: { status: 502 }, [`GET ${PERIODS}`]: { status: 502 } })
    renderAt('/')

    expect(await screen.findByText(en.charts.unavailable)).toBeInTheDocument()
    expect(await screen.findByText(en.periods.unavailable)).toBeInTheDocument()
  })

  it('says the first period closes at its time while none is over', async () => {
    control()
    renderAt('/')

    expect(await screen.findByTestId('current-period')).toHaveTextContent(en.periods.firstCloses(hoursMinutes('2026-11-11T11:01:00Z')))
  })

  it('lists the periods with their verdicts and opens the report of one on a click', async () => {
    control({
      [`GET ${PERIODS}`]: { status: 200, body: { periods: [period()] } },
      [`GET ${PERIOD}`]: { status: 200, body: { period: period(), report: report() } },
    })
    renderAt('/')

    const row = await screen.findByRole('button', { name: new RegExp(en.periods.verdict.BALANCED) })
    expect(row).toHaveTextContent(en.periods.transfers(count(1_440_000)))
    fireEvent.click(row)

    expect(await screen.findByTestId('verdict')).toHaveTextContent(en.report.headline.BALANCED)
  })

  it('names why a failed period has no verdict', async () => {
    const failed = period({ state: 'FAILED', verdict: null, failure: 'DRAIN_TIMED_OUT', numbers: null })
    control({
      [`GET ${PERIODS}`]: { status: 200, body: { periods: [failed] } },
      [`GET ${PERIOD}`]: { status: 200, body: { period: failed, report: null } },
    })
    renderAt('/')

    fireEvent.click(await screen.findByRole('button', { name: new RegExp(en.periods.state.FAILED) }))

    expect(await screen.findByTestId('period-failed')).toHaveTextContent(en.periods.failure.DRAIN_TIMED_OUT)
  })

  it('shows a legend with the words of the instance dots', async () => {
    control()
    renderAt('/')

    const legend = await screen.findByLabelText('Instances')
    expect(legend).toHaveTextContent('consuming')
    expect(legend).toHaveTextContent('no answer to Prometheus')
  })
})

describe('session page', () => {
  it('offers the speeds and lengths of the session, the defaults chosen, and the crash button', async () => {
    control()
    renderAt(`/s/${SESSION_ID}`)

    expect(await raise()).toBeEnabled()
    expect(screen.getByRole('combobox', { name: en.controls.tpsLabel })).toHaveValue('1000')
    expect(screen.getByRole('combobox', { name: en.controls.secondsLabel })).toHaveValue('10')
    expect(within(screen.getByRole('combobox', { name: en.controls.tpsLabel })).getAllByRole('option').map((o) => o.textContent)).toEqual(
      ['900', '1,000', '2,000'].map(en.controls.tpsOption),
    )
    expect(await crash()).toBeEnabled()
  })

  it('raises the load with the speed and length chosen and says until when', async () => {
    const until = '2026-11-11T10:31:00Z'
    const calls = control({ [`POST ${SESSION}/load`]: { status: 202, body: { tps: 2000, until } } })
    renderAt(`/s/${SESSION_ID}`)
    const button = await raise()

    fireEvent.change(screen.getByRole('combobox', { name: en.controls.tpsLabel }), { target: { value: '2000' } })
    fireEvent.change(screen.getByRole('combobox', { name: en.controls.secondsLabel }), { target: { value: '30' } })
    fireEvent.click(button)

    expect(await screen.findByRole('alert')).toHaveTextContent(en.controls.raised('2,000', clock(until)))
    expect(calls.find((c) => c.method === 'POST')?.body).toEqual({ tps: 2000, seconds: 30 })
  })

  it('holds the load button while the session says a pulse or its pause runs', async () => {
    control({ [`GET ${SESSION}`]: { status: 200, body: session({ loadReadyInSeconds: 42 }) } })
    renderAt(`/s/${SESSION_ID}`)

    const button = await raise()
    await waitFor(() => expect(button).toBeDisabled())
    expect(button).toHaveTextContent(en.controls.availableIn(42))
  })

  it.each([
    [{ status: 429, body: { error: 'load-running' }, headers: { 'Retry-After': '65' } }, en.controls.load['load-running'](65)],
    [{ status: 429, body: { error: 'load-used-recently' }, headers: { 'Retry-After': '30' } }, en.controls.load['load-used-recently'](30)],
    [{ status: 400, body: { error: 'invalid-load' } }, en.controls.load['invalid-load']],
    [{ status: 503, body: { error: 'generator-did-not-answer' } }, en.controls.load['generator-did-not-answer']],
    [{ status: 403, body: { error: 'invite not valid' } }, en.controls.inviteNotValid],
  ])('says in words why the load was not raised: %j', async (answer, text) => {
    control({ [`POST ${SESSION}/load`]: answer })
    renderAt(`/s/${SESSION_ID}`)

    fireEvent.click(await raise())

    expect(await screen.findByRole('alert')).toHaveTextContent(text)
  })

  it("holds the button for a refusal's Retry-After, though the session still says ready, then asks the session again", async () => {
    const calls = control({ [`POST ${SESSION}/load`]: { status: 429, body: { error: 'load-running' }, headers: { 'Retry-After': '2' } } })
    renderAt(`/s/${SESSION_ID}`)
    const button = await raise()

    fireEvent.click(button)

    await waitFor(() => expect(button).toHaveTextContent(en.controls.availableIn(2)))
    expect(button).toBeDisabled()
    const sessionReads = calls.filter((c) => c.path === SESSION).length
    await waitFor(() => expect(calls.filter((c) => c.path === SESSION).length).toBeGreaterThan(sessionReads), { timeout: 3500 })
    await waitFor(() => expect(button).toBeEnabled())
  })

  it('keeps the crash button free while the load waits for its answer', async () => {
    control({ [`POST ${SESSION}/load`]: () => new Promise<Answer>(() => {}) })
    renderAt(`/s/${SESSION_ID}`)

    fireEvent.click(await raise())

    await waitFor(() => expect(screen.getByRole('button', { name: new RegExp(en.controls.raise) })).toBeDisabled())
    expect(await crash()).toBeEnabled()
  })

  it('crashes the ledger and says the bank goes on', async () => {
    control({ [`POST ${SESSION}/chaos`]: { status: 202, body: { at: '2026-11-11T10:30:00Z', instances: ['ledger-0', 'ledger-1'] } } })
    renderAt(`/s/${SESSION_ID}`)

    fireEvent.click(await crash())

    expect(await screen.findByRole('alert')).toHaveTextContent(en.controls.crashed(2))
  })

  it('says when the crash may be pressed again after a refusal', async () => {
    control({ [`POST ${SESSION}/chaos`]: { status: 429, body: { error: 'chaos used recently' }, headers: { 'Retry-After': '80' } } })
    renderAt(`/s/${SESSION_ID}`)

    fireEvent.click(await crash())

    expect(await screen.findByRole('alert')).toHaveTextContent(en.controls.chaos.usedRecently(80))
  })

  it('names an unknown session, offers a new link and still shows the bank', async () => {
    control({ [`GET ${SESSION}`]: { status: 404, body: { error: 'session not found' } } })
    renderAt(`/s/${SESSION_ID}`)

    expect(await screen.findByRole('alert')).toHaveTextContent(en.sessionEnded.message)
    expect(screen.getByRole('button', { name: 'Get a link' })).toBeInTheDocument()
    expect(await screen.findByTestId('bank-status')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: new RegExp(en.controls.raise) })).not.toBeInTheDocument()
  })

  it('holds both buttons and offers a new link once the invite has expired', async () => {
    control({ [`GET ${SESSION}`]: { status: 200, body: session({ inviteValid: false }) } })
    renderAt(`/s/${SESSION_ID}`)

    expect(await screen.findByRole('button', { name: 'Get a link' })).toBeInTheDocument()
    expect(await raise()).toBeDisabled()
    expect(await crash()).toBeDisabled()
  })

  it('shows the clients\' speed but no line offering a link on a session page', async () => {
    control()
    renderAt(`/s/${SESSION_ID}`)

    expect(await screen.findByTestId('clients-speed')).toHaveTextContent(en.bank.speed('400'))
    expect(screen.queryByRole('button', { name: en.landing.tryIt })).not.toBeInTheDocument()
  })

  it('shows no link form while the invite is valid', async () => {
    control()
    renderAt(`/s/${SESSION_ID}`)

    await raise()
    expect(screen.queryByRole('button', { name: 'Get a link' })).not.toBeInTheDocument()
  })

  it('says the connection is lost when the session poll gets an unexpected status, not that the session ended', async () => {
    control({ [`GET ${SESSION}`]: { status: 502 } })
    renderAt(`/s/${SESSION_ID}`)

    expect(await screen.findByRole('status')).toHaveTextContent(/Connection lost/)
    expect(screen.queryByText(en.sessionEnded.message)).not.toBeInTheDocument()
  })
})
