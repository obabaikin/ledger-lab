import type { BankView } from './api/client'
import { REPOSITORY_URL } from './Diagram'
import { count, dayAndMonth, hoursMinutes, MS_PER_SECOND, percent, utcDayAndMonth } from './format'
import { t } from './texts'

const VALUE_DIGITS = 2
const AVAILABILITY_TARGET_DIGITS = 1
const POSTING_TARGET_DIGITS = 0
const BUDGET_DIGITS = 0
const BUSY_DIGITS = 2
/** Below half of the budget used a level is on track; above 90 % it is at risk. */
const WARN_FROM = 0.5
const BAD_ABOVE = 0.9
const MS_PER_DAY = 86_400_000
const BAR_WIDTH = 10
const BAR_GAP = 2
const BARS_HEIGHT = 40
const SLO_URL = `${REPOSITORY_URL}/blob/main/docs/guides/slo.md`

type Health = keyof typeof t.availability.health
type Day = BankView['slo']['days'][number]
type Posting = BankView['posting']

export const health = (used: number, missed: boolean): Health =>
  missed || used > BAD_ABOVE ? 'bad' : used >= WARN_FROM ? 'warn' : 'ok'

const isoDay = (ms: number) => new Date(ms).toISOString().slice(0, 10)

/**
 * One slot per day of the window, today the last. A bar's height is the day's share of the whole window's budget; the
 * dashed line is an even share, so a bar above it spent more than its day's part.
 */
function DayBars(props: { days: Day[]; windowDays: number; budget: number; label: (date: string, count: string) => string; now: number; state: Health }) {
  const { days, windowDays, budget } = props
  const even = budget / windowDays
  const top = Math.max(even, ...days.map((d) => d.count))
  const byDay = new Map(days.map((d) => [d.day, d.count]))
  const today = Math.floor(props.now / MS_PER_DAY) * MS_PER_DAY
  const width = windowDays * (BAR_WIDTH + BAR_GAP)
  const y = (value: number) => BARS_HEIGHT - (top === 0 ? 0 : (value / top) * BARS_HEIGHT)
  return (
    <div className={`dayBars ${props.state}`}>
      <svg viewBox={`0 0 ${width} ${BARS_HEIGHT}`} preserveAspectRatio="none">
        {Array.from({ length: windowDays }, (_, i) => {
          const day = isoDay(today - (windowDays - 1 - i) * MS_PER_DAY)
          const value = byDay.get(day)
          if (value === undefined) return null
          const label = props.label(utcDayAndMonth(day), count(value))
          return (
            <rect key={day} data-testid="day-bar" x={i * (BAR_WIDTH + BAR_GAP)} y={y(value)} width={BAR_WIDTH} height={BARS_HEIGHT - y(value)} aria-label={label}>
              <title>{label}</title>
            </rect>
          )
        })}
        <line data-testid="even-pace" className="evenPace" x1={0} x2={width} y1={y(even)} y2={y(even)} />
      </svg>
      <p className="cap dayCaps">
        <span>{t.availability.daysAgo(windowDays)}</span>
        <span>{t.availability.evenPace}</span>
        <span>{t.availability.today}</span>
      </p>
    </div>
  )
}

type CardProps = {
  testId: string
  title: string
  value: string | null
  sub: string
  used: number | null
  usedText: string
  missed: boolean
  days: Day[]
  windowDays: number
  budget: number
  dayLabel: (date: string, count: string) => string
}

function Card(p: CardProps) {
  const state = p.used === null ? null : health(p.used, p.missed)
  return (
    <div className="sloCard" data-testid={p.testId}>
      <h4>{p.title}</h4>
      <p className="sloValue" data-testid={`${p.testId}-value`}>
        {state && <span className={`dot ${state}`} role="img" aria-label={t.availability.health[state]} />}
        {p.value ?? t.availability.notMeasured}
      </p>
      <p className="cap">{p.sub}</p>
      {p.used !== null && state && (
        <>
          <div className="budgetRow">
            <div className="budgetBar" role="meter" aria-label={t.availability.budgetUsed} aria-valuemin={0} aria-valuemax={1} aria-valuenow={Math.min(p.used, 1)}>
              <i className={state} style={{ width: `${Math.min(p.used, 1) * 100}%` }} />
            </div>
            <span className="used" data-testid={`${p.testId}-used`}>{p.usedText}</span>
          </div>
          <p className="cap">{t.availability.budgetUsed}</p>
          <DayBars days={p.days} windowDays={p.windowDays} budget={p.budget} label={p.dayLabel} now={Date.now()} state={state} />
        </>
      )}
    </div>
  )
}

function postingCards(posting: Posting) {
  return posting.targets.map((target, index) => {
    const goal = percent(target.target, POSTING_TARGET_DIGITS)
    return (
      <Card
        key={target.thresholdMs}
        testId={`slo-posting-${index}`}
        title={t.availability.postingTitle(target.thresholdMs / MS_PER_SECOND)}
        value={target.share === null ? null : percent(target.share, VALUE_DIGITS)}
        sub={t.availability.ofTransfers(goal)}
        used={target.budgetUsed}
        usedText={target.budgetUsed === null ? '' : percent(target.budgetUsed, BUDGET_DIGITS)}
        missed={target.share !== null && target.share < target.target}
        days={target.days}
        windowDays={posting.windowDays}
        budget={posting.judged * (1 - target.target)}
        dayLabel={t.availability.slowTransfersOn}
      />
    )
  })
}

export function Availability({ bank }: { bank: BankView }) {
  const { slo, posting, maintenance } = bank
  return (
    <section className="card" aria-label={t.availability.heading}>
      <h3>{t.availability.heading}</h3>
      {posting.since !== null && (
        <p className="hint" data-testid="posting-since">
          {t.availability.postingSince(dayAndMonth(posting.since))}
        </p>
      )}
      <div className="sloCards">
        <Card
          testId="slo-availability"
          title={t.availability.availabilityTitle}
          value={slo.availability === null ? null : percent(slo.availability, VALUE_DIGITS)}
          sub={t.availability.ofMinutes(percent(slo.target, AVAILABILITY_TARGET_DIGITS))}
          used={slo.availability === null ? null : slo.badMinutes / slo.budgetMinutes}
          usedText={t.availability.usedMinutes(slo.badMinutes, slo.budgetMinutes)}
          missed={slo.availability !== null && slo.availability < slo.target}
          days={slo.days}
          windowDays={slo.windowDays}
          budget={slo.budgetMinutes}
          dayLabel={t.availability.badMinutesOn}
        />
        {postingCards(posting)}
      </div>
      <p className="hint sloFoot">
        {slo.busyShare !== null && <span>{t.availability.busy(percent(slo.busyShare, BUSY_DIGITS))}</span>}
        <span data-testid="posting-split">{t.availability.postingSplit(count(posting.planned), count(posting.outside))}</span>
        <span data-testid="maintenance">
          {maintenance === null
            ? t.availability.noUpdate
            : t.availability.update(dayAndMonth(maintenance.from), hoursMinutes(maintenance.from), hoursMinutes(maintenance.to))}
        </span>
      </p>
      <details className="hint">
        <summary>{t.availability.ruleSummary}</summary>
        <p>
          {t.availability.rule}{' '}
          <a href={SLO_URL} target="_blank" rel="noopener">
            {t.availability.howMeasured}
          </a>
        </p>
      </details>
    </section>
  )
}
