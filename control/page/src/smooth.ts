import { useEffect, useRef, useState } from 'react'

export type Counters = Record<string, number | null>

/**
 * Moves from the counters shown when the new snapshot came towards it, never past it: no extrapolation.
 * Rounded down, so shown accepted − posted − rejected stays ≥ 0 when every part moves by the same fraction.
 */
export function between<T extends Counters>(from: T | null, to: T, fraction: number): T {
  const f = Math.min(1, Math.max(0, fraction))
  const out: Counters = {}
  for (const [key, end] of Object.entries(to)) {
    const start = from?.[key] ?? end
    out[key] = end === null || start === null || start > end ? end : Math.floor(start + (end - start) * f)
  }
  return out as T
}

export function useSmoothed<T extends Counters>(target: T | null, intervalMs: number): T | null {
  const [shown, setShown] = useState<T | null>(target)
  const shownRef = useRef<T | null>(target)
  shownRef.current = shown

  useEffect(() => {
    if (target === null) {
      setShown(null)
      return
    }
    const from = shownRef.current
    const startedAt = performance.now()
    let frame = 0
    const step = (now: number) => {
      const fraction = (now - startedAt) / intervalMs
      setShown(between(from, target, fraction))
      if (fraction < 1) frame = requestAnimationFrame(step)
    }
    frame = requestAnimationFrame(step)
    return () => cancelAnimationFrame(frame)
    // A new snapshot is a new object; its identity is the trigger.
  }, [target, intervalMs])

  return shown
}
