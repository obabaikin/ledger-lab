import { render, screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { report } from './test/fakeControl'
import { Verdict, verdictLine } from './Verdict'

describe('verdict line', () => {
  it('says what the bank accepted of what the clients sent and how fast it answered, from the report', () => {
    expect(verdictLine(report())).toBe('Accepted 995 of 1,000 sent (99.5 %) · bank p50 0.01 s / p99 0.48 s')
  })

  it('shows a dash for the clients\' side when the client was lost', () => {
    expect(verdictLine(report({ client: null, bankLatencyP99Ms: null }))).toBe('Accepted 995 of — sent (—) · bank p50 0.01 s / p99 —')
  })

  it('stands under the verdict word', () => {
    render(<Verdict report={report({ result: 'BREAKS' })} tps={1000} durationSeconds={30} />)
    expect(screen.getByTestId('verdict-line')).toHaveTextContent('Accepted 995 of 1,000 sent')
  })
})

describe('verdict', () => {
  it('shows BALANCED with the sums of the books and every check passed', () => {
    render(<Verdict report={report()} tps={1000} durationSeconds={30} />)

    expect(screen.getByText('Every cent accounted for')).toBeInTheDocument()
    expect(screen.getByText('€980.00 = €980.00')).toBeInTheDocument()
    expect(screen.getByText('€100,000.00 = €100,000.00')).toBeInTheDocument()
    expect(screen.queryByLabelText('Reasons')).not.toBeInTheDocument()
    expect(screen.queryByText('✗')).not.toBeInTheDocument()
  })

  it('shows BREAKS with ✗ at the failed check of the books', () => {
    render(
      <Verdict
        report={report({
          result: 'BREAKS',
          ledgerBalanced: false,
          ledgerFailedChecks: ['TOTAL_BALANCE_CONSERVED'],
          ledger: { posted: 980, rejected: 15, openingTotalCents: 10_000_000, balanceTotalCents: 9_999_900, debitsCents: 98_000, creditsCents: 98_000 },
        })}
        tps={1000}
        durationSeconds={30}
      />,
    )

    expect(screen.getByText('The records do not agree')).toBeInTheDocument()
    expect(screen.getByLabelText('Σ balances before = after: ✗')).toBeInTheDocument()
    expect(screen.getByLabelText('Σ debits = Σ credits: ✓')).toBeInTheDocument()
  })

  it('shows INCOMPLETE with the reasons word for word', () => {
    const reasons = ['drain timed out: ledger applied 900, gateway results 900, of 995', 'client lost']
    render(<Verdict report={report({ result: 'INCOMPLETE', reasons, clientBalanced: null, client: null })} tps={100} durationSeconds={10} />)

    expect(screen.getByText('Not everything could be checked')).toBeInTheDocument()
    const shownReasons = within(screen.getByLabelText('Reasons')).getAllByRole('listitem').map((li) => li.textContent)
    expect(shownReasons).toEqual(reasons)
    expect(screen.getByLabelText("clients' journal = bank's statement: –")).toBeInTheDocument()
  })
})
