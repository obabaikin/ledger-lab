import { useCallback, useEffect, useRef } from 'react'

/**
 * Calls `poll` now and then every `intervalMs` while `active`. The returned function calls it at once, also while not
 * active, as after a start that makes the page active. One call at a time: a tick due while the last call still waits
 * for its answer is skipped.
 */
export function usePolling(poll: () => Promise<unknown>, intervalMs: number, active: boolean): () => Promise<void> {
  const latest = useRef(poll)
  latest.current = poll
  const busy = useRef(false)
  const again = useRef(false)

  const run = useCallback(async () => {
    busy.current = true
    do {
      again.current = false
      await latest.current().catch(() => undefined)
    } while (again.current)
    busy.current = false
  }, [])

  // An answer already on its way may predate what made the caller ask, so the call follows it rather than being skipped.
  const pollNow = useCallback(async () => {
    if (busy.current) again.current = true
    else await run()
  }, [run])

  useEffect(() => {
    if (!active) return
    let timer: ReturnType<typeof setTimeout> | undefined
    let stopped = false
    const next = async () => {
      if (!busy.current) await run()
      if (!stopped) timer = setTimeout(next, intervalMs)
    }
    void next()
    return () => {
      stopped = true
      clearTimeout(timer)
    }
  }, [intervalMs, active, run])

  return pollNow
}
