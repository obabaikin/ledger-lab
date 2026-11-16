import type { BankView } from './api/client'

type Clients = NonNullable<BankView['clients']>

export type Speed = { tps: number; pulse: boolean }

/** One source for the heading and the summary bar, so both show the same number from the same answer. */
export const clientsSpeed = (clients: Clients | null): Speed | null =>
  clients === null ? null : clients.pulse ? { tps: clients.pulse.tps, pulse: true } : { tps: clients.targetTps, pulse: false }
