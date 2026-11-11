import { useCallback, useRef, useState, type ReactNode } from 'react'
import { api, type BankView, type PeriodView, type SeriesView } from './api/client'
import { Availability } from './Availability'
import { BuildInfo } from './BuildInfo'
import { Charts } from './Charts'
import { flowRates, stopLedgerWires, type Flow } from './FlowDots'
import { dayAndMonth, hoursMinutes, MS_PER_SECOND } from './format'
import { Hero } from './Hero'
import { LiveScheme } from './LiveScheme'
import { Periods } from './Periods'
import { t } from './texts'
import { usePolling } from './usePolling'

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

export function BankPage(props: { controls?: ReactNode; top?: ReactNode; after?: ReactNode; sessionLost?: boolean; pressedCrashAt?: number | null }) {
  const [live, setLive] = useState<Live | null>(null)
  const [bankLost, setBankLost] = useState(false)
  const [periods, setPeriods] = useState<PeriodView[] | null>(null)
  const [series, setSeries] = useState<SeriesView | null>(null)
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
        if (!fresh) return { bank, rates: last?.rates ?? flowRates(null, null, 0), ratesFrom: last?.ratesFrom ?? null }
        const seconds = prev === null ? 0 : (at - prev.at) / MS_PER_SECOND
        return { bank, rates: flowRates(prev?.flow ?? null, flow, seconds), ratesFrom: prev?.at ?? null }
      })
      setBankLost(false)
    } catch {
      setBankLost(true)
    }
  }, [])

  const pollPeriods = useCallback(async () => setPeriods((await api.periods()).periods), [])
  const pollSeries = useCallback(async () => setSeries(await api.series()), [])

  usePolling(pollBank, BANK_POLL_MS, true)
  usePolling(pollPeriods, PERIODS_POLL_MS, true)
  usePolling(pollSeries, SERIES_POLL_MS, true)

  const lost = bankLost || props.sessionLost === true
  const bank = live?.bank ?? null
  const lastCrash = bank?.chaos.recent.at(-1)
  const crashAt = Math.max(props.pressedCrashAt ?? 0, lastCrash ? Date.parse(lastCrash.at) : 0) || null
  const rates = live && stopLedgerWires(live.rates, crashAt, live.ratesFrom)

  return (
    <main aria-busy={bank === null}>
      <Hero />
      {props.top}
      {props.controls}
      {lost && (
        <p role="status" className="banner">
          {bank === null ? t.connection.lostBeforeFirst : t.connection.lost}
        </p>
      )}
      {bank && (
        <>
          <section className="card">
            <p className="status" data-testid="bank-status">
              {bank.startedAt === null
                ? t.bank.notStarted
                : t.bank.running(`${dayAndMonth(bank.startedAt)}, ${hoursMinutes(bank.startedAt)}`, age(Date.parse(bank.startedAt), Date.now()))}
            </p>
            <LiveScheme bank={bank} rates={rates ?? undefined} intervalMs={BANK_POLL_MS} />
          </section>
          <Charts view={series} chaos={bank.chaos.recent} />
          <Availability bank={bank} />
          <Periods bank={bank} periods={periods} />
        </>
      )}
      {props.after}
      <BuildInfo />
    </main>
  )
}
