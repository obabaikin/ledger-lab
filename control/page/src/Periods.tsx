import { useEffect, useState } from 'react'
import { api, type BankView, type PeriodDetails, type PeriodView } from './api/client'
import { count, dayAndMonth, hoursMinutes } from './format'
import type { Section } from './sections'
import { t } from './texts'
import { Verdict } from './Verdict'

const PERIODS: Section = 'periods'

type Read = { start: string; details: PeriodDetails | null; failed: boolean }

function outcome(p: PeriodView): string {
  if (p.verdict !== null) return t.periods.verdict[p.verdict]
  return t.periods.state[p.state]
}

const FINISHED: PeriodView['state'][] = ['RECONCILED', 'FAILED']

/** A period still being reconciled is passed over: it has not failed, it is not done yet. */
export function lastFinished(periods: PeriodView[] | null): { balanced: boolean; outcome: string } | null {
  const last = periods?.find((p) => FINISHED.includes(p.state))
  return last === undefined ? null : { balanced: last.verdict === 'BALANCED', outcome: outcome(last) }
}

function Failed({ period }: { period: PeriodView }) {
  return (
    <section className="card verdict" data-testid="period-failed">
      <div className="mark bad">✗</div>
      <h2>{t.periods.failedHeading}</h2>
      {period.failure !== null && <p>{t.periods.failure[period.failure]}</p>}
    </section>
  )
}

export function Periods({ bank, periods, lost }: { bank: BankView; periods: PeriodView[] | null; lost: boolean }) {
  const [selected, setSelected] = useState<string | null>(null)
  const [read, setRead] = useState<Read | null>(null)
  const listedState = periods?.find((p) => p.start === selected)?.state

  useEffect(() => {
    if (selected === null) return
    let current = true
    api
      .period(selected)
      .then((details) => current && setRead({ start: selected, details, failed: false }))
      .catch(() => current && setRead({ start: selected, details: null, failed: true }))
    return () => {
      current = false
    }
  }, [selected, listedState])

  const { currentPeriod } = bank
  const shown = read !== null && read.start === selected ? read : null

  return (
    <section className="card periods" id={PERIODS} aria-label={t.periods.heading}>
      <h2>{t.periods.heading}</h2>
      <p className="question">
        {t.periods.question}{' '}
        <span data-testid="current-period">
          {periods !== null && periods.length === 0
            ? t.periods.firstCloses(hoursMinutes(currentPeriod.closesAt))
            : t.periods.current(hoursMinutes(currentPeriod.start), hoursMinutes(currentPeriod.end), hoursMinutes(currentPeriod.closesAt))}
        </span>
      </p>
      {lost && <p className="hint">{t.periods.unavailable}</p>}
      {periods !== null && periods.length > 0 && (
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>{t.periods.columns.period}</th>
                <th>{t.periods.columns.transfers}</th>
                <th>{t.periods.columns.verdict}</th>
              </tr>
            </thead>
            <tbody>
              {periods.map((p) => (
                <tr
                  key={p.start}
                  className={p.start === selected ? 'selected' : ''}
                  role="button"
                  aria-pressed={p.start === selected}
                  onClick={() => setSelected(p.start === selected ? null : p.start)}
                >
                  <td>
                    {dayAndMonth(p.start)}, {t.periods.range(hoursMinutes(p.start), hoursMinutes(p.end))}
                  </td>
                  <td>{p.numbers === null ? '' : t.periods.transfers(count(p.numbers.accepted))}</td>
                  <td className={p.verdict === 'BALANCED' ? 'ok' : p.verdict === 'MISMATCH' || p.state === 'FAILED' ? 'bad' : ''}>{outcome(p)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {selected !== null && shown === null && <p className="hint">{t.periods.loading}</p>}
      {shown?.failed && (
        <p role="alert" className="hint">
          {t.periods.notRead}
        </p>
      )}
      {shown?.details?.period.state === 'FAILED' && <Failed period={shown.details.period} />}
      {shown?.details?.report && <Verdict report={shown.details.report} />}
    </section>
  )
}
