import { useMemo } from 'react'
import type { BankView } from './api/client'
import { Diagram, PodLegend } from './Diagram'
import { clock, count, dayAndMonth, euros, hoursMinutes, shown } from './format'
import { bankCounters } from './shownCounts'
import { useSmoothed } from './smooth'
import { t } from './texts'

/**
 * The diagram and the counters under it. The counters move every frame between two snapshots, so they live here: a frame
 * redraws this part, not the page.
 */
export function LiveScheme(props: { bank: BankView; rates: number[] | undefined; intervalMs: number }) {
  const { bank } = props
  const target = useMemo(() => bankCounters(bank), [bank])
  const counters = useSmoothed(target, props.intervalMs)
  const { books, clients } = bank
  const lastCrash = bank.chaos.recent.at(-1)
  const rejections = Object.entries(bank.totals.rejectionsByReason).filter(([, n]) => n > 0)

  return (
    <>
      <Diagram
        numbers={{
          requested: counters?.requested ?? null,
          clientsSince: clients?.since ?? null,
          accepted: counters?.accepted ?? null,
          posted: counters?.posted ?? null,
          rejected: counters?.rejected ?? null,
          refused: counters?.refused ?? null,
        }}
        pods={bank.pods}
        rates={props.rates}
      />
      <PodLegend />
      <div className="money">
        <span>
          {t.bank.moved} <b data-testid="moved">{shown(counters?.movedCents, euros)}</b>
        </span>
        <span>
          <b>{shown(counters?.accepted)}</b> {t.bank.transfers}
        </span>
        {books && (
          <>
            <span>
              <b>{count(books.accounts)}</b> {t.bank.accounts}
            </span>
            <span data-testid="books">
              {t.bank.totalInAccounts} <b>{euros(books.balanceTotalCents)}</b>{' '}
              {books.balanceTotalCents === books.openingTotalCents ? (
                <span className="ok">{t.bank.unchanged}</span>
              ) : (
                <span className="bad">{t.bank.was(euros(books.openingTotalCents))}</span>
              )}
            </span>
          </>
        )}
      </div>
      <p className="hint center" data-testid="rejections">
        {rejections.length === 0
          ? t.bank.noRejections
          : `${t.bank.rejected(count(bank.totals.rejected))}: ${rejections.map(([reason, n]) => `${t.bank.rejectReason[reason] ?? reason} ${count(n)}`).join(', ')}`}
      </p>
      {clients && (
        <p className="hint center" data-testid="clients">
          {clients.pulse
            ? t.bank.pulse(count(clients.pulse.tps), clock(clients.pulse.until), count(clients.baseTps))
            : t.bank.clientsSending(count(clients.targetTps))}
          {clients.since && <> {t.bank.gaveUp(count(clients.gaveUp), `${dayAndMonth(clients.since)}, ${hoursMinutes(clients.since)}`)}</>}
        </p>
      )}
      {lastCrash && (
        <p className="hint center" data-testid="last-crash">
          {t.bank.lastCrash(clock(lastCrash.at), lastCrash.instances.length)}
        </p>
      )}
    </>
  )
}
