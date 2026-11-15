import type { PeriodReport } from './api/client'
import { REPOSITORY_URL } from './Diagram'
import { clock, count, euros } from './format'
import { t } from './texts'

const TESTS_URL = `${REPOSITORY_URL}/tree/main/control/src/test`

const HEADLINE: Record<PeriodReport['verdict'], { mark: string; tone: string }> = {
  BALANCED: { mark: '✓', tone: 'ok' },
  MISMATCH: { mark: '✗', tone: 'bad' },
}

type Check = { rule: string; value: string; passed: boolean | null }

function checks(r: PeriodReport): Check[] {
  const failed = new Set(r.ledgerCheck.failedChecks)
  const { ledgerCheck: check, gateway, ledger, books, client } = r
  const notes = new Set(r.notes)
  return [
    {
      rule: t.report.debitsEqualCredits,
      value: `${euros(check.debitsCents)} = ${euros(check.creditsCents)}`,
      passed: !failed.has('JOURNAL_DEBITS_EQUAL_CREDITS'),
    },
    {
      rule: t.report.everyTransferBooked,
      value: t.report.withoutJournal(count(check.transfersWithInvalidJournal)),
      passed: !failed.has('JOURNAL_MATCHES_TRANSFER_LOG'),
    },
    {
      rule: t.report.everyAccount,
      value: check.accountsCompared ? t.report.accountsOutOfBalance(count(check.accountsOutOfBalance)) : t.report.notCompared,
      passed: check.accountsCompared ? !failed.has('EVERY_ACCOUNT_MATCHES_JOURNAL') : null,
    },
    {
      rule: t.report.totalUnchanged,
      value: `${euros(books.openingTotalCents)} = ${euros(books.balanceTotalCents)}`,
      passed: books.balanced,
    },
    {
      rule: t.report.acceptedIsBookedPlusRejected,
      value: `${count(gateway.accepted)} = ${count(ledger.postedTransfers)} + ${count(ledger.rejectedTransfers)}`,
      passed: !r.mismatches.includes('BANK_VIEWS_DISAGREE'),
    },
    {
      rule: t.report.clientsAgree,
      value: t.report.unknownSettled(count(client.unknownResolved.accepted), count(client.unknownResolved.notAccepted)),
      passed: client.balanced,
    },
    {
      rule: t.report.projection,
      value: r.stats === null || notes.has('REPORTING_UNAVAILABLE') ? t.report.notChecked : notes.has('PROJECTION_BEHIND_BOOKS') ? t.report.behind : count(r.stats.posted),
      // The projection may trail the books without a break, so it never shows ✗; the note says why.
      passed: r.stats !== null && !notes.has('PROJECTION_BEHIND_BOOKS') && !notes.has('REPORTING_UNAVAILABLE') ? true : null,
    },
  ]
}

function transferExamples(r: PeriodReport): [string, string[]][] {
  const b = r.client.breaks
  const kinds: [string, string[]][] = [
    [t.report.breaks.notInJournal, b.notInJournal.transferIds],
    [t.report.breaks.acknowledgedNotInStatement, b.acknowledgedNotInStatement.transferIds],
    [t.report.breaks.failedButInStatement, b.failedButInStatement.transferIds],
    [t.report.breaks.payloadMismatch, b.payloadMismatch.examples.map((e) => e.transferId)],
    [t.report.breaks.statusMismatch, b.statusMismatch.examples.map((e) => e.transferId)],
    [t.report.breaks.invalidJournal, r.ledgerCheck.invalidJournalExamples],
  ]
  return kinds.filter(([, ids]) => ids.length > 0)
}

function lostOrDoubled(r: PeriodReport): number {
  const b = r.client.breaks
  return b.notInJournal.count + b.acknowledgedNotInStatement.count + b.failedButInStatement.count + b.payloadMismatch.count + b.statusMismatch.count
}

const MARK = (passed: boolean | null) => (passed === null ? '–' : passed ? '✓' : '✗')

export function Verdict({ report }: { report: PeriodReport }) {
  const head = HEADLINE[report.verdict]
  const breaks = lostOrDoubled(report)
  const rejected = Object.entries(report.rejectionsByReason).filter(([, n]) => n > 0)
  const examples = transferExamples(report)
  const facts: [string, string][] = []
  if (report.stats) facts.push(['€', `${t.bank.moved} ${euros(report.stats.postedAmountCents)}`])
  if (report.ledger.poolPostings > 0) facts.push(['↔', t.report.poolPostings(count(report.ledger.poolPostings))])
  for (const c of report.chaos) facts.push(['⚡', t.report.crashedAt(clock(c.at), c.instances.length)])
  return (
    <section className="card verdict" data-testid="verdict">
      <div className={`mark ${head.tone}`}>{head.mark}</div>
      <h2>{t.report.headline[report.verdict]}</h2>
      {report.mismatches.length > 0 && (
        <ul className="reasons" aria-label={t.report.mismatchesLabel}>
          {report.mismatches.map((m) => (
            <li key={m}>{t.report.mismatch[m]}</li>
          ))}
        </ul>
      )}
      <div className="bigs">
        <div className="big">
          <b>{count(report.gateway.accepted)}</b>
          <span>{t.report.transfers}</span>
        </div>
        <div className="big">
          <b className={breaks > 0 ? 'bad' : 'ok'} data-testid="lost-or-doubled">
            {count(breaks)}
          </b>
          <span>{t.report.lostOrDoubled}</span>
        </div>
        <div className="big">
          <b className={report.ledgerCheck.accountsOutOfBalance > 0 ? 'bad' : 'ok'}>
            {report.ledgerCheck.accountsCompared ? count(report.ledgerCheck.accountsOutOfBalance) : '–'}
          </b>
          <span>{t.report.accountsOff}</span>
        </div>
      </div>
      {facts.length > 0 && (
        <ul className="facts">
          {facts.map(([icon, text]) => (
            <li key={text}>
              <span className="icon">{icon}</span>
              <span>{text}</span>
            </li>
          ))}
        </ul>
      )}
      {report.notes.map((note) => (
        <p key={note} className="hint">
          {t.report.note[note]}
        </p>
      ))}
      <details>
        <summary>{t.report.details}</summary>
        <h4>{t.report.checksHeading}</h4>
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
          <p className="hint">
            {t.report.rejectedByReason}: {rejected.map(([reason, n]) => `${t.bank.rejectReason[reason] ?? reason} ${count(n)}`).join(', ')}
          </p>
        )}
        {(examples.length > 0 || report.ledgerCheck.accountExamples.length > 0) && (
          <>
            <h4>{t.report.examplesHeading}</h4>
            <ul className="examples" data-testid="examples">
              {report.ledgerCheck.accountExamples.map((a) => (
                <li key={`account-${a.accountNo}`}>{t.report.accountExample(a.accountNo, euros(a.balanceChangeCents), euros(a.journalCents))}</li>
              ))}
              {examples.map(([kind, ids]) => (
                <li key={kind}>
                  {kind}: <code>{ids.join(', ')}</code>
                </li>
              ))}
            </ul>
          </>
        )}
        <p className="hint">
          {t.report.testsBefore}
          <a href={TESTS_URL} target="_blank" rel="noopener">
            {t.report.testsLink}
          </a>
          {t.report.testsAfter}
        </p>
      </details>
    </section>
  )
}
