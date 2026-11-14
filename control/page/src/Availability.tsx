import type { BankView } from './api/client'
import { REPOSITORY_URL } from './Diagram'
import { dayAndMonth, hoursMinutes, MS_PER_SECOND, percent } from './format'
import { t } from './texts'

const AVAILABILITY_DIGITS = 2
const TARGET_DIGITS = 1
const BUSY_DIGITS = 2
const SLO_URL = `${REPOSITORY_URL}/blob/main/docs/guides/slo.md`

type Budget = { badMinutes: number; budgetMinutes: number; plannedMinutes: number; outsideMinutes: number }

function BudgetRow({ budget, testId }: { budget: Budget; testId: string }) {
  const { badMinutes, budgetMinutes } = budget
  const used = Math.min(badMinutes, budgetMinutes)
  return (
    <>
      <p data-testid={testId}>{t.availability.budget(badMinutes, budgetMinutes)}</p>
      <div
        className="budgetBar"
        role="meter"
        aria-label={t.availability.budget(badMinutes, budgetMinutes)}
        aria-valuemin={0}
        aria-valuemax={budgetMinutes}
        aria-valuenow={used}
      >
        <i className={badMinutes >= budgetMinutes ? 'spent' : ''} style={{ width: `${budgetMinutes === 0 ? 100 : (used / budgetMinutes) * 100}%` }} />
      </div>
      <p className="hint" data-testid={`${testId}-split`}>
        {t.availability.split(badMinutes, budget.plannedMinutes, budget.outsideMinutes)}
      </p>
    </>
  )
}

export function Availability({ bank }: { bank: BankView }) {
  const { slo, freshness, maintenance } = bank
  return (
    <section className="card" aria-label={t.availability.heading}>
      <h3>{t.availability.heading}</h3>
      <h4>{t.availability.availabilityTitle}</h4>
      <p>
        {slo.availability === null
          ? t.availability.notMeasured
          : t.availability.value(percent(slo.availability, AVAILABILITY_DIGITS), slo.windowDays, percent(slo.target, TARGET_DIGITS))}
      </p>
      <BudgetRow budget={slo} testId="budget" />
      {slo.busyShare !== null && <p className="hint">{t.availability.busy(percent(slo.busyShare, BUSY_DIGITS))}</p>}
      <h4>{t.availability.freshnessTitle(freshness.thresholdMs / MS_PER_SECOND)}</h4>
      <BudgetRow budget={freshness} testId="freshness-budget" />
      <p data-testid="maintenance">
        {maintenance === null
          ? t.availability.noUpdate
          : t.availability.update(dayAndMonth(maintenance.from), hoursMinutes(maintenance.from), hoursMinutes(maintenance.to))}
      </p>
      <p className="hint">
        {t.availability.rule}{' '}
        <a href={SLO_URL} target="_blank" rel="noopener">
          {t.availability.howMeasured}
        </a>
      </p>
    </section>
  )
}
