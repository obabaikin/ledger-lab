import { MS_PER_SECOND } from './format'

/** Rounding up on the server plus the answer's way back; a deadline later by less than this is the same deadline. */
const SAME_DEADLINE_MS = 2 * MS_PER_SECOND

/** The page counts down a deadline it already knows; the server's whole seconds may end it, never stretch it. */
export function nextChaosReadyAt(knownAt: number, serverSeconds: number, now: number): number {
  if (serverSeconds === 0) return 0
  const serverAt = now + serverSeconds * MS_PER_SECOND
  return serverAt > knownAt + SAME_DEADLINE_MS ? serverAt : knownAt
}

/** Seconds to show while the server has not said ready; null once it has. The page's own count never says ready. */
export function chaosWaitShown(serverSeconds: number, knownAt: number, now: number): number | null {
  if (serverSeconds === 0) return null
  return Math.max(1, Math.ceil((knownAt - now) / MS_PER_SECOND))
}
