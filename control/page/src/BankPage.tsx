import { useCallback, useRef, useState, type ReactNode } from 'react'
import { api, DEFAULT_CHART_RANGE, type BankView, type ChartRange, type PeriodView, type SeriesView } from './api/client'
import { Availability } from './Availability'
import { BuildInfo } from './BuildInfo'
import { Charts } from './Charts'
import { flowRates, NO_FLOW, stopLedgerWires, type Flow } from './FlowDots'
import { dayAndMonth, hoursMinutes, MS_PER_SECOND } from './format'
import { Hero } from './Hero'
import { LiveScheme } from './LiveScheme'
import { Periods } from './Periods'
import { SummaryBar } from './SummaryBar'
import type { Section } from './sections'
import { t } from './texts'
import { usePolling } from './usePolling'

const LIVE: Section = 'live'
const CHARTS: Section = 'charts'
const BANK_POLL_MS = 1000
const PERIODS_POLL_MS = 5000
const SERIES_POLL_MS = 15000
const SECONDS_PER_MINUTE = 60
const MINUTES_PER_HOUR = 60
const HOURS_PER_DAY = 24

type Snapshot = { at: number; flow: Flow | null }
type Live = { bank: BankView; rates: number[]; ratesFrom: number | null }

function flowOf(bank: BankView): Flow | null {
  if (bank.clients === null) return null
  const { totals, clients } = bank
  return {
    requested: clients.requested,
    accepted: totals.accepted,
    finished: totals.posted + totals.rejected,
    rejected: totals.rejected,
    acknowledged: clients.acknowledged,
  }
}

export function age(fromMs: number, nowMs: number): string {
  const minutes = Math.max(0, Math.floor((nowMs - fromMs) / MS_PER_SECOND / SECONDS_PER_MINUTE))
  const hours = Math.floor(minutes / MINUTES_PER_HOUR)
  return t.bank.age(Math.floor(hours / HOURS_PER_DAY), hours % HOURS_PER_DAY, minutes % MINUTES_PER_HOUR)
}

export function BankPage(props: {
  controls?: ReactNode
  top?: ReactNode
  invite?: ReactNode
  after?: ReactNode
  sessionLost?: boolean
  pressedCrashAt?: number | null
}) {
  const [live, setLive] = useState<Live | null>(null)
  const [bankLost, setBankLost] = useState(false)
  const [periods, setPeriods] = useState<PeriodView[] | null>(null)
  const [series, setSeries] = useState<SeriesView | null>(null)
  const [periodsLost, setPeriodsLost] = useState(false)
  const [seriesLost, setSeriesLost] = useState(false)
  const [range, setRange] = useState<ChartRange>(DEFAULT_CHART_RANGE)
  const shownRange = useRef(range)
  const previous = useRef<Snapshot | null>(null)

  const pollBank = useCallback(async () => {
    try {
      const bank = await api.bank()
      const at = bank.readAt === null ? null : Date.parse(bank.readAt)
      const prev = previous.current
      // The same reading again leaves the dots as they were: a rate of 0 would stop them between two readings.
      const fresh = at !== null && prev?.at !== at
      const flow = flowOf(bank)
      if (fresh) previous.current = { at, flow }
      setLive((last) => {
        if (!fresh) return { bank, rates: last?.rates ?? NO_FLOW, ratesFrom: last?.ratesFrom ?? null }
        const seconds = prev === null ? 0 : (at - prev.at) / MS_PER_SECOND
        return { bank, rates: flowRates(prev?.flow ?? null, flow, seconds), ratesFrom: prev?.at ?? null }
      })
      setBankLost(false)
    } catch {
      // A rate across the gap would be the outage's average, not the flow of now.
      previous.current = null
      setBankLost(true)
    }
  }, [])

  const pollPeriods = useCallback(async () => {
    try {
      setPeriods((await api.periods()).periods)
      setPeriodsLost(false)
    } catch {
      setPeriodsLost(true)
    }
  }, [])
  const pollSeries = useCallback(async () => {
    const asked = shownRange.current
    try {
      const view = await api.series(asked)
      // An answer for the range shown before the visitor picked another is dropped.
      if (asked !== shownRange.current) return
      setSeries(view)
      setSeriesLost(false)
    } catch {
      if (asked === shownRange.current) setSeriesLost(true)
    }
  }, [])

  usePolling(pollBank, BANK_POLL_MS, true)
  usePolling(pollPeriods, PERIODS_POLL_MS, true)
  const pollSeriesNow = usePolling(pollSeries, SERIES_POLL_MS, true)
  const pickRange = (picked: ChartRange) => {
    shownRange.current = picked
    setRange(picked)
    setSeries(null)
    void pollSeriesNow()
  }

  const lost = bankLost || props.sessionLost === true
  const bank = live?.bank ?? null
  const lastCrash = bank?.chaos.recent.at(-1)
  const crashAt = Math.max(props.pressedCrashAt ?? 0, lastCrash ? Date.parse(lastCrash.at) : 0) || null
  const rates = live && (lost ? NO_FLOW : stopLedgerWires(live.rates, crashAt, live.ratesFrom))

  return (
    <main aria-busy={bank === null}>
      <Hero bank={bank} action={props.invite} />
      {props.top}
      <SummaryBar bank={bank} periods={periods} />
      {bank === null && props.controls}
      {lost && (
        <p role="status" className="banner">
          {bank === null ? t.connection.lostBeforeFirst : t.connection.lost}
        </p>
      )}
      {bank && (
        <>
          <section className="card" id={LIVE} aria-label={t.bank.heading}>
            <h2>{t.bank.heading}</h2>
            <p className="question">
              {t.bank.question}{' '}
              <span data-testid="bank-status">
                {bank.startedAt === null
                  ? t.bank.notStarted
                  : t.bank.running(`${dayAndMonth(bank.startedAt)}, ${hoursMinutes(bank.startedAt)}`, age(Date.parse(bank.startedAt), Date.now()))}
              </span>
            </p>
            {props.controls}
            <LiveScheme bank={bank} rates={rates ?? undefined} intervalMs={BANK_POLL_MS} />
          </section>
          <section className="card" id={CHARTS} aria-label={t.charts.heading}>
            <h2>{t.charts.heading}</h2>
            <p className="question">{t.charts.question}</p>
            {seriesLost && <p className="hint">{t.charts.unavailable}</p>}
            <Charts view={series} chaos={bank.chaos.recent} range={range} onRange={pickRange} />
          </section>
          <Availability bank={bank} />
          <Periods bank={bank} periods={periods} lost={periodsLost} />
        </>
      )}
      {props.after}
      <BuildInfo />
    </main>
  )
}
