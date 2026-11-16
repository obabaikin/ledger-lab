import { useEffect, useState, type ReactNode } from 'react'
import type { BankView, PeriodView } from './api/client'
import { availabilityLevel, postingLevel, type Level } from './Availability'
import { MS_PER_SECOND, NO_DATA, rate } from './format'
import { lastFinished } from './Periods'
import { SECTIONS, type Section } from './sections'
import { clientsSpeed } from './speed'
import { t } from './texts'

const CURRENT_BAND = '-80px 0px -70% 0px'

function useCurrentSection(shown: boolean): Section | null {
  const [current, setCurrent] = useState<Section | null>(null)
  useEffect(() => {
    if (!shown || typeof IntersectionObserver === 'undefined') return
    const observer = new IntersectionObserver(
      (entries) => {
        const crossing = entries.filter((e) => e.isIntersecting).at(-1)
        const id = SECTIONS.find((s) => s === crossing?.target.id)
        if (id) setCurrent(id)
      },
      { rootMargin: CURRENT_BAND },
    )
    for (const id of SECTIONS) {
      const section = document.getElementById(id)
      if (section) observer.observe(section)
    }
    return () => observer.disconnect()
  }, [shown])
  return current
}

function Item(props: { to: Section; testId: string; tone: string; name: string; value: string; mark?: ReactNode }) {
  const full = props.value === '' ? props.name : `${props.name} ${props.value}`
  return (
    <a href={`#${props.to}`} data-testid={props.testId} className={props.tone} title={full} aria-label={full}>
      {props.mark ?? <i className={`dot ${props.tone}`} />}
      <span className="full">{props.name}</span> <span className="value">{props.value}</span>
    </a>
  )
}

const levelItem = (testId: string, name: string, level: Level) => (
  <Item key={testId} to="slo" testId={testId} tone={level.state ?? 'none'} name={name} value={level.value ?? NO_DATA} />
)

/** Not shown before the bank is read: the sections it links to are not on the page yet. */
export function SummaryBar({ bank, periods }: { bank: BankView | null; periods: PeriodView[] | null }) {
  const current = useCurrentSection(bank !== null)
  if (bank === null) return null
  const speed = clientsSpeed(bank.clients)
  const last = lastFinished(periods)
  return (
    <nav className="summaryBar" aria-label={t.summary.label}>
      <div className="sectionLinks">
        {SECTIONS.map((s) => (
          <a key={s} href={`#${s}`} className={s === current ? 'on' : ''} aria-current={s === current ? 'location' : undefined}>
            {t.summary.sections[s]}
          </a>
        ))}
      </div>
      <div className="sum">
        <Item
          to="live"
          testId="sum-now"
          tone={speed === null ? 'none' : speed.pulse ? 'warn' : 'flow'}
          name={t.summary.now}
          value={speed === null ? NO_DATA : speed.pulse ? t.summary.pulse(rate(speed.tps)) : t.summary.speed(rate(speed.tps))}
        />
        {levelItem('sum-availability', t.summary.availability, availabilityLevel(bank.slo))}
        {bank.posting.targets.map((target, i) => levelItem(`sum-posting-${i}`, t.summary.within(target.thresholdMs / MS_PER_SECOND), postingLevel(target)))}
        {last && (
          <Item
            to="periods"
            testId="sum-period"
            tone={last.balanced ? 'ok' : 'bad'}
            name={last.balanced ? t.summary.lastBalanced : t.summary.lastPeriod(last.outcome)}
            value=""
            mark={<span className="mark">{last.balanced ? '✓' : '✗'}</span>}
          />
        )}
      </div>
    </nav>
  )
}
