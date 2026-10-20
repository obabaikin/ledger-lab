import type { RunView } from './api/client'

export const PHASES = ['Queued', 'Clients sending', 'Bank finishing', 'Verdict'] as const

export const CLIENTS_SENDING_PHASE = 1
export const BANK_FINISHING_PHASE = 2
export const VERDICT_PHASE = PHASES.length - 1

export function phaseOf(run: Pick<RunView, 'state'>): number {
  switch (run.state) {
    case 'QUEUED':
    case 'CREATED':
      return 0
    case 'RUNNING':
      return CLIENTS_SENDING_PHASE
    case 'DRAINING':
      return BANK_FINISHING_PHASE
    default:
      return VERDICT_PHASE
  }
}

export const phaseName = (run: Pick<RunView, 'state'>) => PHASES[phaseOf(run)]

export const booksFinal = (run: Pick<RunView, 'state' | 'booksFinalAt'>) => run.state === 'DRAINING' && run.booksFinalAt !== null
