import { DOT_TRAVEL_MS } from './FlowDots'

/** The start page's illustration of a crash: invented rates, no numbers shown (ADR 0014). */

type ScenePhase = 'flowing' | 'crashed' | 'catching-up'

export type Scene = { phase: ScenePhase; ledgerLook: 'dead' | 'recovering' | null; rates: number[] }

export const SCENE_CYCLE_MS = 10_000
export const CRASH_AT_MS = 2_000
export const BACK_AT_MS = 5_000
const CAUGHT_UP_AT_MS = 7_500
/** A shown rate, transfers per second: enough dots to read the way without a crowd. */
const RATE = 150
const CATCH_UP_FACTOR = 3
const REJECTED_SHARE = 0.02

/** What the ledger takes from Kafka per second at [t] within the cycle. */
function ledgerIntake(t: number): number {
  const phase = phaseAt(t)
  if (phase === 'crashed') return 0
  return phase === 'catching-up' ? RATE * CATCH_UP_FACTOR : RATE
}

/**
 * Along the wires of FlowDots: the clients and the gateway go on while the ledger is down, Kafka holds the rest.
 * Each hop on the way back starts and stops one dot's travel after the hop before it: a result reaches the gateway
 * only after the ledger booked the transfer and Kafka got its result.
 */
function ratesAt(t: number): number[] {
  const booked = (at: number) => (phaseAt(at) === 'crashed' ? 0 : ledgerIntake(at - DOT_TRAVEL_MS))
  const out = booked(t)
  const answered = booked(t - DOT_TRAVEL_MS)
  return [RATE, RATE, ledgerIntake(t), out * (1 - REJECTED_SHARE), out * REJECTED_SHARE, answered * (1 - REJECTED_SHARE), RATE]
}

function phaseAt(t: number): ScenePhase {
  const inCycle = ((t % SCENE_CYCLE_MS) + SCENE_CYCLE_MS) % SCENE_CYCLE_MS
  if (inCycle >= CRASH_AT_MS && inCycle < BACK_AT_MS) return 'crashed'
  if (inCycle >= BACK_AT_MS && inCycle < CAUGHT_UP_AT_MS) return 'catching-up'
  return 'flowing'
}

const LEDGER_LOOK: Record<ScenePhase, Scene['ledgerLook']> = { flowing: null, crashed: 'dead', 'catching-up': 'recovering' }

export function sceneAt(elapsedMs: number): Scene {
  const phase = phaseAt(elapsedMs)
  return { phase, ledgerLook: LEDGER_LOOK[phase], rates: ratesAt(elapsedMs) }
}

export function sameScene(a: Scene, b: Scene): boolean {
  return a.phase === b.phase && a.rates.every((rate, wire) => rate === b.rates[wire])
}
