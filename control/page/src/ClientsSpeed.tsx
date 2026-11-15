import type { BankView } from './api/client'
import { clock, count } from './format'
import { t } from './texts'

type Clients = NonNullable<BankView['clients']>

export function ClientsSpeed(props: { clients: Clients }) {
  const { pulse, targetTps, baseTps } = props.clients
  return (
    <p className="speed" data-testid="clients-speed">
      <b>{t.bank.speed(count(pulse ? pulse.tps : targetTps))}</b>{' '}
      <span className="hint">{pulse ? t.bank.speedUntil(clock(pulse.until), count(baseTps)) : t.bank.speedNow}</span>
    </p>
  )
}
