import { useEffect, useRef } from 'react'

export type Flow = { requested: number; accepted: number; finished: number; rejected: number; acknowledged: number }

/** Per second, along the wires in the diagram's order: out to the ledger, the bin, then the way back. */
export function flowRates(prev: Flow | null, cur: Flow | null, seconds: number): number[] {
  if (prev === null || cur === null || seconds <= 0) return WIRE_COUNT_ZEROS
  const per = (key: keyof Flow) => Math.max(0, cur[key] - prev[key]) / seconds
  return [per('requested'), per('accepted'), per('finished'), per('finished'), per('rejected'), per('finished'), per('acknowledged')]
}

/** Kafka → ledger, ledger → Kafka, ledger → bin, Kafka → gateway: what a crashed ledger stops. */
const LEDGER_WIRES = new Set([2, 3, 4, 5])

/**
 * From a crash on, the ledger's wires stand still until a whole interval between snapshots lies after it: the rate of an
 * interval that began before the crash still counts bookings made before it.
 */
export function stopLedgerWires(rates: number[], crashAt: number | null, intervalFrom: number | null): number[] {
  if (crashAt === null || (intervalFrom !== null && intervalFrom >= crashAt)) return rates
  return rates.map((rate, wire) => (LEDGER_WIRES.has(wire) ? 0 : rate))
}

const WIRE_COUNT_ZEROS = [0, 0, 0, 0, 0, 0, 0]
const SVG_NS = 'http://www.w3.org/2000/svg'
const DOT_TRAVEL_MS = 600
const RATE_CAP = 2000
const DOTS_PER_THOUSAND = 30
const REJECTED_WIRE = 4
/** About 1 in 100 transfers is rejected: at the common scale its dot would hardly ever show. */
const BOOST: Record<number, number> = { [REJECTED_WIRE]: 6 }
const COLOR = ['var(--flow)', 'var(--flow)', 'var(--flow)', 'var(--ok)', 'var(--bad)', 'var(--ok)', 'var(--ok)']

type Dot = { el: SVGCircleElement; path: SVGPathElement; length: number; born: number }

export function FlowDots({ rates }: { rates: number[] }) {
  const layer = useRef<SVGGElement>(null)
  const latest = useRef(rates)
  latest.current = rates

  useEffect(() => {
    const g = layer.current
    const svg = g?.ownerSVGElement
    if (!g || !svg) return
    const paths = Array.from(svg.querySelectorAll<SVGPathElement>('path.wire'))
    if (paths.some((p) => typeof p.getTotalLength !== 'function')) return
    const dots: Dot[] = []
    const owed = paths.map(() => 0)
    let last = performance.now()
    let frame = 0
    const tick = (now: number) => {
      const dt = Math.min(now - last, 100) / 1000
      last = now
      paths.forEach((path, k) => {
        let due = (owed[k] ?? 0) + (Math.min(latest.current[k] ?? 0, RATE_CAP) / 1000) * DOTS_PER_THOUSAND * (BOOST[k] ?? 1) * dt
        for (; due >= 1; due -= 1) {
          const el = document.createElementNS(SVG_NS, 'circle')
          el.setAttribute('r', k === REJECTED_WIRE ? '4' : '3')
          el.style.fill = COLOR[k] ?? 'var(--flow)'
          g.appendChild(el)
          dots.push({ el, path, length: path.getTotalLength(), born: now })
        }
        owed[k] = due
      })
      for (let i = dots.length - 1; i >= 0; i--) {
        const d = dots[i]!
        const f = (now - d.born) / DOT_TRAVEL_MS
        if (f >= 1) {
          d.el.remove()
          dots.splice(i, 1)
          continue
        }
        const p = d.path.getPointAtLength(f * d.length)
        d.el.setAttribute('cx', String(p.x))
        d.el.setAttribute('cy', String(p.y))
      }
      frame = requestAnimationFrame(tick)
    }
    frame = requestAnimationFrame(tick)
    return () => {
      cancelAnimationFrame(frame)
      dots.forEach((d) => d.el.remove())
    }
  }, [])

  return <g ref={layer} />
}
