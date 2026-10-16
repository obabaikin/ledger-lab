import type { ChaosInRun, SecondStats } from './api/client'
import { count } from './format'
import { fullBankSeconds, fullClientSeconds } from './fullSeconds'

export type Point = [number, number | null]
type Line = { name: string; color: string; points: Point[] }

const W = 420
const H = 170
const LEFT = 44
const BOTTOM = 22
const TOP = 8
const MS_PER_SECOND = 1000
const HEADROOM = 1.15
const MIN_TOP = 10

/** A second without a value breaks the line: the gap is drawn as a gap, not as 0. */
export function segments(points: Point[]): [number, number][][] {
  const out: [number, number][][] = []
  let current: [number, number][] = []
  for (const [s, v] of points) {
    if (v === null) {
      if (current.length > 0) out.push(current)
      current = []
    } else {
      current.push([s, v])
    }
  }
  if (current.length > 0) out.push(current)
  return out
}

function Chart({ lines, maxSecond, bands }: { lines: Line[]; maxSecond: number; bands: [number, number][] }) {
  const values = lines.flatMap((l) => l.points.map(([, v]) => v).filter((v): v is number => v !== null))
  const top = Math.max(MIN_TOP, ...values) * HEADROOM
  const x = (s: number) => LEFT + (W - LEFT - 8) * (s / Math.max(maxSecond, 1))
  const y = (v: number) => TOP + (H - TOP - BOTTOM) * (1 - v / top)
  return (
    <svg viewBox={`0 0 ${W} ${H}`}>
      {[0, 1, 2, 3].map((g) => (
        <g key={g}>
          <line x1={LEFT} x2={W - 8} y1={y((top * g) / 3)} y2={y((top * g) / 3)} stroke="var(--line)" />
          <text x={LEFT - 6} y={y((top * g) / 3) + 4} textAnchor="end" fontSize={10} fill="var(--muted)">
            {count((top * g) / 3)}
          </text>
        </g>
      ))}
      <text x={LEFT} y={H - 4} fontSize={10} fill="var(--muted)">
        0 s
      </text>
      <text x={W - 8} y={H - 4} fontSize={10} fill="var(--muted)" textAnchor="end">
        {maxSecond} s
      </text>
      {bands.map(([from, to]) => (
        <g key={from}>
          <rect x={x(from)} y={TOP} width={Math.max(2, x(to) - x(from))} height={H - TOP - BOTTOM} fill="var(--bad)" opacity={0.12} />
          <text x={x(from) + 3} y={TOP + 12} fontSize={10} fill="var(--bad)">
            ledger crashed
          </text>
        </g>
      ))}
      {lines.flatMap((l) =>
        segments(l.points).map((segment) =>
          segment.length === 1 ? (
            <circle key={`${l.name}-${segment[0]![0]}`} cx={x(segment[0]![0])} cy={y(segment[0]![1])} r={1.8} fill={l.color} />
          ) : (
            <polyline
              key={`${l.name}-${segment[0]![0]}`}
              points={segment.map(([s, v]) => `${x(s)},${y(v)}`).join(' ')}
              fill="none"
              stroke={l.color}
              strokeWidth={2.2}
              strokeLinejoin="round"
            />
          ),
        ),
      )}
    </svg>
  )
}

function Legend({ lines }: { lines: Line[] }) {
  return (
    <div className="legend">
      {lines.map((l) => (
        <span key={l.name}>
          <i style={{ background: l.color }} />
          {l.name}
        </span>
      ))}
    </div>
  )
}

/** Instances still book for a moment after the crash; the outage starts with the first second without bookings. */
const OUTAGE_STARTS_WITHIN_SECONDS = 2

/** A crash lasts until the start of the first second in which the ledger booked again after its outage. */
export function crashBands(chaos: ChaosInRun[], booked: Point[], lastSecond: number): [number, number][] {
  return chaos.map((c) => {
    const from = c.offsetMs / MS_PER_SECOND
    const after = booked.filter(([s, v]) => s > from && v !== null)
    const outage = after.findIndex(([s, v]) => v === 0 && s <= from + 1 + OUTAGE_STARTS_WITHIN_SECONDS)
    if (outage < 0) return [from, from + 1]
    const back = after.slice(outage).find(([, v]) => v !== null && v > 0)
    return [from, back === undefined ? lastSecond : back[0] - 1]
  })
}

export function Charts(props: {
  startedAt: string | null
  durationSeconds: number
  series: SecondStats[]
  seriesAt: string | null
  sending: boolean
  clientsPerSecond: number[] | null
  chaos: ChaosInRun[]
}) {
  const start = props.startedAt === null ? null : Date.parse(props.startedAt)
  // A second's point sits at its end, as "how many in that second".
  const at = (second: string) => (start === null ? 0 : (Date.parse(second) - start) / MS_PER_SECOND + 1)
  const clientsEndAt = start === null ? null : start + props.durationSeconds * MS_PER_SECOND
  const series = props.seriesAt === null ? [] : fullBankSeconds(props.series, Date.parse(props.seriesAt), clientsEndAt)
  const accepted: Point[] = series.map((s) => [at(s.second), s.accepted])
  const booked: Point[] = series.map((s) => [at(s.second), s.posted])
  const clients: Point[] = fullClientSeconds(props.clientsPerSecond ?? [], props.durationSeconds, props.sending).map((n, i) => [i + 1, n])
  const p50: Point[] = series.map((s) => [at(s.second), s.bankP50Ms])
  const p99: Point[] = series.map((s) => [at(s.second), s.bankP99Ms])
  const lastSecond = Math.max(props.durationSeconds, ...accepted.map(([s]) => s), clients.length)
  const maxSecond = Math.ceil(lastSecond)
  const bands = crashBands(props.chaos, booked, lastSecond)

  const clientsLine: Line = { name: 'sent by clients', color: 'var(--muted)', points: clients }
  const acceptedLine: Line = { name: 'accepted by the gateway', color: 'var(--flow)', points: accepted }
  const bookedLine: Line = { name: 'booked by the ledger', color: 'var(--ok)', points: booked }
  const rate = [clientsLine, acceptedLine, bookedLine]
  const latency: Line[] = [
    { name: 'median (p50)', color: 'var(--flow)', points: p50 },
    { name: 'slowest 1% (p99)', color: 'var(--warn)', points: p99 },
  ]

  return (
    <section className="charts">
      <div className="card chart">
        <h3>Transfers per second</h3>
        <Legend lines={rate} />
        <Chart lines={rate} maxSecond={maxSecond} bands={bands} />
      </div>
      <div className="card chart">
        <h3>Time gateway - ledger, ms</h3>
        <Legend lines={latency} />
        <Chart lines={latency} maxSecond={maxSecond} bands={bands} />
      </div>
    </section>
  )
}
