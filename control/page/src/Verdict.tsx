import type { RunReport } from './api/client'
import { REPOSITORY_URL } from './Diagram'
import { count, euros } from './format'

const TESTS_URL = `${REPOSITORY_URL}/tree/main/control/src/test`
const MS_PER_SECOND = 1000

const HEADLINE: Record<RunReport['result'], { mark: string; title: string; tone: string }> = {
  BALANCED: { mark: '✓', title: 'Every cent accounted for', tone: 'ok' },
  BREAKS: { mark: '✗', title: 'The records do not agree', tone: 'bad' },
  INCOMPLETE: { mark: '?', title: 'Not everything could be checked', tone: 'warn' },
}

type Check = { rule: string; value: string; passed: boolean | null }

function checks(r: RunReport): Check[] {
  const failed = new Set(r.ledgerFailedChecks)
  const { ledger, gateway, client } = r
  return [
    {
      rule: 'Σ debits = Σ credits',
      value: `${euros(ledger.debitsCents)} = ${euros(ledger.creditsCents)}`,
      passed: !failed.has('JOURNAL_SUMS_TO_ZERO'),
    },
    {
      rule: 'Σ balances before = after',
      value: `${euros(ledger.openingTotalCents)} = ${euros(ledger.balanceTotalCents)}`,
      passed: !failed.has('TOTAL_BALANCE_CONSERVED'),
    },
    { rule: 'every transfer has its debit and credit', value: `${count(ledger.posted)} booked`, passed: !failed.has('JOURNAL_MATCHES_TRANSFER_LOG') },
    { rule: 'every balance = opening + its journal', value: '', passed: !failed.has('EVERY_ACCOUNT_MATCHES_JOURNAL') },
    {
      rule: 'accepted = booked + rejected',
      value: `${count(gateway.accepted)} = ${count(ledger.posted)} + ${count(ledger.rejected)}`,
      passed: r.bankViewsAgree,
    },
    {
      rule: "clients' journal = bank's statement",
      value: client ? `${count(client.acknowledged)} acknowledged · ${count(client.unknown)} unknown, settled` : 'not checked',
      passed: r.clientBalanced,
    },
  ]
}

const MARK = (passed: boolean | null) => (passed === null ? '–' : passed ? '✓' : '✗')

export function Verdict({ report, tps, durationSeconds }: { report: RunReport; tps: number; durationSeconds: number }) {
  const head = HEADLINE[report.result]
  const agree = [report.ledgerBalanced, report.bankViewsAgree, report.clientBalanced].filter((v) => v === true).length
  const breaks = report.breaks ? Object.values(report.breaks).reduce((n, ids) => n + ids.count, 0) : null
  const rejected = Object.entries(report.rejectionsByReason)
  const facts: [string, string][] = [
    ['€', `${euros(report.ledger.debitsCents)} moved · every debit has its credit`],
    ['✕', `${count(report.ledger.rejected)} bad transfers caught by the rules`],
  ]
  if (report.ledgerDuplicatesAtLeast) facts.push(['↺', `≥ ${count(report.ledgerDuplicatesAtLeast)} redeliveries skipped by the ledger`])
  for (const c of report.chaos) {
    facts.push(['⚡', `ledger crashed at ${(c.offsetMs / MS_PER_SECOND).toFixed(1)} s (${c.instances.length} instances)`])
  }
  return (
    <section className="card verdict" data-testid="verdict">
      <div className={`mark ${head.tone}`}>{head.mark}</div>
      <h2>{head.title}</h2>
      <div className="hint">
        {count(tps)} transfers/s for {durationSeconds} s
      </div>
      {report.reasons.length > 0 && (
        <ul className="reasons" aria-label="Reasons">
          {report.reasons.map((reason) => (
            <li key={reason}>{reason}</li>
          ))}
        </ul>
      )}
      <div className="bigs">
        <div className="big">
          <b>{count(report.gateway.accepted)}</b>
          <span>transfers</span>
        </div>
        <div className="big">
          <b className={breaks ? 'bad' : 'ok'}>{breaks === null ? '–' : count(breaks)}</b>
          <span>lost or doubled</span>
        </div>
        <div className="big">
          <b>{agree} of 3</b>
          <span>independent records agree</span>
        </div>
      </div>
      <ul className="facts">
        {facts.map(([icon, text]) => (
          <li key={text}>
            <span className="icon">{icon}</span>
            <span>{text}</span>
          </li>
        ))}
      </ul>
      <details>
        <summary>Details</summary>
        <h4>Accounting invariants, checked after the run</h4>
        <div className="table-wrap">
          <table className="inv">
            <tbody>
              {checks(report).map((c) => (
                <tr key={c.rule}>
                  <td className="sym">{c.rule}</td>
                  <td>{c.value}</td>
                  <td className={`mark ${c.passed === false ? 'bad' : ''}`} aria-label={`${c.rule}: ${MARK(c.passed)}`}>
                    {MARK(c.passed)}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        {rejected.length > 0 && (
          <p className="hint">Rejected by reason: {rejected.map(([reason, n]) => `${reason.toLowerCase().replaceAll('_', ' ')} ${count(n)}`).join(', ')}</p>
        )}
        {report.notes.map((note) => (
          <p key={note} className="hint">
            {note}
          </p>
        ))}
        <h4>Guarantees</h4>
        <div className="table-wrap">
          <table className="guar">
            <tbody>
              <tr><td>Client → Gateway</td><td>idempotent requests: a repeat gets the same transfer</td></tr>
              <tr><td>Gateway → Kafka</td><td>durable handoff: stored first, published from the outbox</td></tr>
              <tr><td>Kafka → Ledger</td><td>at-least-once delivery: a message may come twice</td></tr>
              <tr><td>Ledger booking</td><td>exactly-once effect: a repeat is skipped, a booking is one transaction</td></tr>
              <tr><td>Reconciliation</td><td>detects divergence between clients, gateway and ledger</td></tr>
            </tbody>
          </table>
        </div>
        <h4>Three sides of the run</h4>
        <div className="table-wrap">
          <table>
            <thead>
              <tr><th /><th>Sent</th><th>Accepted</th><th>Booked</th><th>Rejected</th></tr>
            </thead>
            <tbody>
              <tr>
                <td>Clients' journal</td>
                <td>{report.client ? count(report.client.requested) : '–'}</td>
                <td>{report.client ? count(report.client.acknowledged) : '–'}</td>
                <td /><td />
              </tr>
              <tr>
                <td>Gateway</td><td />
                <td>{count(report.gateway.accepted)}</td>
                <td>{count(report.gateway.posted)}</td>
                <td>{count(report.gateway.rejected)}</td>
              </tr>
              <tr>
                <td>Ledger</td><td /><td />
                <td>{count(report.ledger.posted)}</td>
                <td>{count(report.ledger.rejected)}</td>
              </tr>
            </tbody>
          </table>
        </div>
        <p className="hint">
          These checks can say no:{' '}
          <a href={TESTS_URL} target="_blank" rel="noopener">
            see the tests
          </a>{' '}
          where a lost, doubled or half-booked transfer makes them fail.
        </p>
      </details>
    </section>
  )
}
