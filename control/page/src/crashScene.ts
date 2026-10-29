/** The start page's illustration of a crash: invented rates, no numbers shown (ADR 0014). */

type ScenePhase = 'flowing' | 'crashed' | 'catching-up'

export type Scene = { phase: ScenePhase; ledgerLook: 'dead' | 'recovering' | null; rates: number[] }

export const SCENE_CYCLE_MS = 10_000
export const CRASH_AT_MS = 2_000
const BACK_AT_MS = 5_000
const CAUGHT_UP_AT_MS = 7_500
/** A shown rate, transfers per second: enough dots to read the way without a crowd. */
const RATE = 150
const CATCH_UP_FACTOR = 3
const REJECTED_SHARE = 0.02

/** Along the wires of FlowDots: the clients and the gateway go on while the ledger is down, Kafka holds the rest. */
function ratesFor(ledger: number): number[] {
  const booked = ledger * (1 - REJECTED_SHARE)
  return [RATE, RATE, ledger, booked, ledger * REJECTED_SHARE, booked, RATE]
}

export function sceneAt(elapsedMs: number): Scene {
  const t = elapsedMs % SCENE_CYCLE_MS
  if (t >= CRASH_AT_MS && t < BACK_AT_MS) return { phase: 'crashed', ledgerLook: 'dead', rates: ratesFor(0) }
  if (t >= BACK_AT_MS && t < CAUGHT_UP_AT_MS) return { phase: 'catching-up', ledgerLook: 'recovering', rates: ratesFor(RATE * CATCH_UP_FACTOR) }
  return { phase: 'flowing', ledgerLook: null, rates: ratesFor(RATE) }
}
