import type { RunView } from './api/client'

const IN_PROGRESS: ReadonlySet<RunView['state']> = new Set(['CREATED', 'RUNNING', 'DRAINING'])

/** The run the viewer picked; else the one running now, the oldest first; else the newest. Runs come newest first. */
export function runToShow(runs: RunView[], pickedId: string | null): RunView | null {
  return runs.find((r) => r.runId === pickedId) ?? runInProgress(runs) ?? runs[0] ?? null
}

export function runInProgress(runs: RunView[]): RunView | null {
  return [...runs].reverse().find((r) => IN_PROGRESS.has(r.state)) ?? null
}

export function otherRunInProgress(runs: RunView[], shown: RunView | null): RunView | null {
  const active = runInProgress(runs)
  return active !== null && active.runId !== shown?.runId ? active : null
}
