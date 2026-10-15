import { useEffect, useRef } from 'react'

/**
 * Calls `poll` now and then every `intervalMs` while `active`; a new `restartKey` calls it now again.
 * One call at a time: a call due while the last one is still waiting for its answer is skipped.
 */
export function usePolling(poll: () => Promise<unknown>, intervalMs: number, active: boolean, restartKey: unknown = null) {
  const latest = useRef(poll)
  latest.current = poll
  const busy = useRef(false)

  useEffect(() => {
    if (!active) return
    let timer: ReturnType<typeof setTimeout> | undefined
    let stopped = false
    const next = async () => {
      if (!busy.current) {
        busy.current = true
        await latest.current().catch(() => undefined)
        busy.current = false
      }
      if (!stopped) timer = setTimeout(next, intervalMs)
    }
    void next()
    return () => {
      stopped = true
      clearTimeout(timer)
    }
  }, [intervalMs, active, restartKey])
}
