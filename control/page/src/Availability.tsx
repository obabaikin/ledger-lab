import type { BankView } from './api/client'
import { REPOSITORY_URL } from './Diagram'
import { count, dayAndMonth, hoursMinutes, MS_PER_SECOND, percent } from './format'
import { t } from './texts'

const AVAILABILITY_DIGITS = 2
const TARGET_DIGITS = 1
const BUSY_DIGITS = 2
const POSTING_DIGITS = 2
const POSTING_TARGET_DIGITS = 0
const BUDGET_DIGITS = 0
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

type Posting = BankView['posting']

function PostingTarget({ target, index }: { target: Posting['targets'][number]; index: number }) {
  const seconds = target.thresholdMs / MS_PER_SECOND
  const goal = percent(target.target, POSTING_TARGET_DIGITS)
  if (target.share === null || target.budgetUsed === null) {
    return <p data-testid={`posting-${index}`}>{t.availability.postingNotMeasured(seconds, goal)}</p>
  }
  const used = percent(target.budgetUsed, BUDGET_DIGITS)
  return (
    <>
      <p data-testid={`posting-${index}`}>{t.availability.postingValue(seconds, percent(target.share, POSTING_DIGITS), goal)}</p>
      <p data-testid={`posting-${index}-budget`}>{t.availability.postingBudget(used)}</p>
      <div
        className="budgetBar"
        role="meter"
        aria-label={t.availability.postingBudget(used)}
        aria-valuemin={0}
        aria-valuemax={1}
        aria-valuenow={Math.min(target.budgetUsed, 1)}
      >
        <i className={target.budgetUsed >= 1 ? 'spent' : ''} style={{ width: `${Math.min(target.budgetUsed, 1) * 100}%` }} />
      </div>
    </>
  )
}

function PostingSection({ posting }: { posting: Posting }) {
  return (
    <>
      <h4>{t.availability.postingTitle}</h4>
      <p className="hint" data-testid="posting-window">
        {posting.since === null ? t.availability.postingWindow(posting.windowDays) : t.availability.postingSince(dayAndMonth(posting.since))}
      </p>
      {posting.targets.map((target, index) => (
        <PostingTarget key={target.thresholdMs} target={target} index={index} />
      ))}
      <p className="hint" data-testid="posting-split">
        {t.availability.postingSplit(count(posting.planned), count(posting.outside))}
      </p>
    </>
  )
}

export function Availability({ bank }: { bank: BankView }) {
  const { slo, posting, maintenance } = bank
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
      <PostingSection posting={posting} />
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
