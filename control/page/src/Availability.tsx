import type { BankView } from './api/client'
import { dayAndMonth, hoursMinutes, percent } from './format'
import { t } from './texts'

const AVAILABILITY_DIGITS = 2
const TARGET_DIGITS = 1
const BUSY_DIGITS = 2

export function Availability({ bank }: { bank: BankView }) {
  const { slo, maintenance } = bank
  const used = Math.min(slo.badMinutes, slo.budgetMinutes)
  return (
    <section className="card" aria-label={t.availability.heading}>
      <h3>{t.availability.heading}</h3>
      <p>
        {slo.availability === null
          ? t.availability.notMeasured
          : t.availability.value(percent(slo.availability, AVAILABILITY_DIGITS), slo.windowDays, percent(slo.target, TARGET_DIGITS))}
      </p>
      <p data-testid="budget">{t.availability.budget(slo.badMinutes, slo.budgetMinutes)}</p>
      <div
        className="budgetBar"
        role="meter"
        aria-label={t.availability.budget(slo.badMinutes, slo.budgetMinutes)}
        aria-valuemin={0}
        aria-valuemax={slo.budgetMinutes}
        aria-valuenow={used}
      >
        <i className={slo.badMinutes >= slo.budgetMinutes ? 'spent' : ''} style={{ width: `${slo.budgetMinutes === 0 ? 100 : (used / slo.budgetMinutes) * 100}%` }} />
      </div>
      <p className="hint">
        {slo.busyShare !== null && <>{t.availability.busy(percent(slo.busyShare, BUSY_DIGITS))} · </>}
        {t.availability.planned(slo.plannedMinutes)}
      </p>
      <p data-testid="maintenance">
        {maintenance === null
          ? t.availability.noUpdate
          : t.availability.update(dayAndMonth(maintenance.from), hoursMinutes(maintenance.from), hoursMinutes(maintenance.to))}
      </p>
      <p className="hint">{t.availability.rule}</p>
    </section>
  )
}
