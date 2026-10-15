import type { RunView } from './api/client'

export const PHASES = ['Queued', 'Clients sending', 'Bank finishing', 'Reconciling', 'Verdict'] as const

export const VERDICT_PHASE = PHASES.length - 1

export function phaseOf(run: Pick<RunView, 'state' | 'booksFinalAt'>): number {
  switch (run.state) {
    case 'QUEUED':
    case 'CREATED':
      return 0
    case 'RUNNING':
      return 1
    case 'DRAINING':
      return run.booksFinalAt === null ? 2 : 3
    default:
      return VERDICT_PHASE
  }
}

export const phaseName = (run: Pick<RunView, 'state' | 'booksFinalAt'>) => PHASES[phaseOf(run)]
