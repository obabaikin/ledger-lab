import type { BankView } from './api/client'
import { clock, count } from './format'
import { t } from './texts'

type Clients = NonNullable<BankView['clients']>

type BaseLoad = NonNullable<BankView['load']>

const MS_PER_MINUTE = 60_000

/** Rounded up and at least 1: an answer read a moment before the change still says it is to come. */
const minutesUntil = (iso: string, now: number) => Math.max(1, Math.ceil((Date.parse(iso) - now) / MS_PER_MINUTE))

export function ClientsSpeed(props: { clients: Clients; load: BaseLoad | null }) {
  const { pulse, targetTps, baseTps } = props.clients
  const { load } = props
  return (
    <>
      <p className="speed" data-testid="clients-speed">
        <b>{t.bank.speed(count(pulse ? pulse.tps : targetTps))}</b>{' '}
        <span className="hint">{pulse ? t.bank.speedUntil(clock(pulse.until), count(baseTps)) : t.bank.speedNow}</span>
      </p>
      {load && (
        <p className="hint" data-testid="base-load">
          {load.nextChangeAt === null
            ? t.bank.base(count(load.baseTps))
            : t.bank.baseChangesIn(count(load.baseTps), minutesUntil(load.nextChangeAt, Date.now()))}
        </p>
      )}
    </>
  )
}
