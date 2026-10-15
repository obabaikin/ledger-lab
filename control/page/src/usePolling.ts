import { useEffect, useRef } from 'react'

/** Calls `poll` now and then every `intervalMs` while `active`; a slow answer never overlaps the next call. */
export function usePolling(poll: () => Promise<unknown>, intervalMs: number, active: boolean) {
  const latest = useRef(poll)
  latest.current = poll

  useEffect(() => {
    if (!active) return
    let timer: ReturnType<typeof setTimeout> | undefined
    let stopped = false
    const next = async () => {
      await latest.current().catch(() => undefined)
      if (!stopped) timer = setTimeout(next, intervalMs)
    }
    void next()
    return () => {
      stopped = true
      clearTimeout(timer)
    }
  }, [intervalMs, active])
}
