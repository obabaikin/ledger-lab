import { render, screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { clock } from './format'
import { report } from './test/fakeControl'
import { en } from './texts/en'
import { Verdict } from './Verdict'

const check = (rule: string) => screen.getByLabelText(new RegExp(`^${rule.replace(/[()+=Σ]/g, '.')}: `))

describe('report of a period', () => {
  it('shows BALANCED with the sums of the books and every check that ran passed', () => {
    render(<Verdict report={report({ stats: { periodStart: '', at: '', accepted: 995, posted: 980, rejected: 15, commandsSeen: 995, postedAmountCents: 98_000, rejectionsByReason: {} } })} />)

    expect(screen.getByText(en.report.headline.BALANCED)).toBeInTheDocument()
    expect(screen.getByText('€980.00 = €980.00')).toBeInTheDocument()
    expect(screen.getByText('€100,000.00 = €100,000.00')).toBeInTheDocument()
    expect(screen.getByText('995 = 980 + 15')).toBeInTheDocument()
    expect(screen.queryByLabelText(en.report.mismatchesLabel)).not.toBeInTheDocument()
    expect(screen.queryByText('✗')).not.toBeInTheDocument()
    expect(screen.getByTestId('lost-or-doubled')).toHaveTextContent('0')
  })

  it('shows MISMATCH with every disagreement in words and the ids of the transfers as examples', () => {
    render(
      <Verdict
        report={report({
          verdict: 'MISMATCH',
          mismatches: ['CLIENT_JOURNAL_DISAGREES'],
          client: {
            ...report().client,
            balanced: false,
            breaks: { ...report().client.breaks, notInJournal: { count: 2, transferIds: ['t-1', 't-2'] } },
          },
        })}
      />,
    )

    expect(screen.getByText(en.report.headline.MISMATCH)).toBeInTheDocument()
    expect(within(screen.getByLabelText(en.report.mismatchesLabel)).getByText(en.report.mismatch.CLIENT_JOURNAL_DISAGREES)).toBeInTheDocument()
    expect(screen.getByTestId('lost-or-doubled')).toHaveTextContent('2')
    expect(check(en.report.clientsAgree)).toHaveTextContent('✗')
    expect(screen.getByTestId('examples')).toHaveTextContent(`${en.report.breaks.notInJournal}: t-1, t-2`)
  })

  it('names the account whose balance changed past its journal', () => {
    const ledgerCheck = {
      ...report().ledgerCheck,
      balanced: false,
      failedChecks: ['EVERY_ACCOUNT_MATCHES_JOURNAL' as const],
      accountsOutOfBalance: 1,
      accountExamples: [{ accountNo: 4711, balanceChangeCents: 500, journalCents: 0 }],
    }
    render(<Verdict report={report({ verdict: 'MISMATCH', mismatches: ['LEDGER_CHECK_FAILED'], ledgerCheck })} />)

    expect(check(en.report.everyAccount)).toHaveTextContent('✗')
    expect(screen.getByTestId('examples')).toHaveTextContent(en.report.accountExample(4711, '€5.00', '€0.00'))
  })

  it('shows accounts not compared as a dash with its note, not as a pass or a break', () => {
    render(<Verdict report={report({ ledgerCheck: { ...report().ledgerCheck, accountsCompared: false }, notes: ['ACCOUNTS_NOT_COMPARED'] })} />)

    expect(check(en.report.everyAccount)).toHaveTextContent('–')
    expect(screen.getByText(en.report.note.ACCOUNTS_NOT_COMPARED)).toBeInTheDocument()
  })

  it('says the projection is behind the books without a ✗, since it may trail them without a break', () => {
    const stats = { periodStart: '', at: '', accepted: 995, posted: 900, rejected: 15, commandsSeen: 995, postedAmountCents: 90_000, rejectionsByReason: {} }
    render(<Verdict report={report({ stats, notes: ['PROJECTION_BEHIND_BOOKS'] })} />)

    expect(check(en.report.projection)).toHaveTextContent('–')
    expect(screen.getByText(en.report.behind)).toBeInTheDocument()
  })

  it('lists the crashes of the ledger and the pool postings in the period', () => {
    render(<Verdict report={report({ chaos: [{ at: '2026-11-11T09:12:00Z', instances: ['ledger-0', 'ledger-1'] }], ledger: { ...report().ledger, poolPostings: 42 } })} />)

    expect(screen.getByText(en.report.poolPostings('42'))).toBeInTheDocument()
    expect(screen.getByText(en.report.crashedAt(clock('2026-11-11T09:12:00Z'), 2))).toBeInTheDocument()
  })

  it('shows the load of the period in one line: the range of the base and the highest pulse that met it', () => {
    render(<Verdict report={report({ load: { minBaseTps: 300, maxBaseTps: 600, maxPulseTps: 1400 } })} />)

    expect(screen.getByText(`${en.report.load(en.report.loadRange('300', '600'))} · ${en.report.loadPulse('1400')}`)).toHaveTextContent('Load 300–600/s · pulse 1400/s')
  })

  it('shows only the range of the base without a pulse, and one number when the base did not change', () => {
    const { unmount } = render(<Verdict report={report({ load: { minBaseTps: 300, maxBaseTps: 600, maxPulseTps: null } })} />)
    expect(screen.getByText(en.report.load(en.report.loadRange('300', '600')))).toBeInTheDocument()
    expect(screen.queryByText(/pulse/)).toBeNull()
    unmount()

    render(<Verdict report={report({ load: { minBaseTps: 400, maxBaseTps: 400, maxPulseTps: null } })} />)
    expect(screen.getByText('Load 400/s')).toBeInTheDocument()
  })

  it('shows no load for a report made before control kept it, or a period without a base told and without a pulse', () => {
    const { unmount } = render(<Verdict report={report({ load: null })} />)
    expect(screen.queryByText(/^Load/)).toBeNull()
    unmount()

    render(<Verdict report={report({ load: { minBaseTps: null, maxBaseTps: null, maxPulseTps: null } })} />)
    expect(screen.queryByText(/^Load/)).toBeNull()
  })
})
