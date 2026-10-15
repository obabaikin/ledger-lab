import type { ChaosInRun, SecondStats } from './api/client'
import { count } from './format'
import { fullBankSeconds, fullClientSeconds } from './fullSeconds'

export type RateLines = 'accepted' | 'clients' | 'all'

export const RATE_LINES: Record<RateLines, string> = {
  accepted: '(a) accepted + booked',
  clients: '(b) clients + booked',
  all: '(c) all three',
}

type Point = [number, number | null]
type Line = { name: string; color: string; points: Point[] }

const W = 420
const H = 170
const LEFT = 44
const BOTTOM = 22
const TOP = 8
const MS_PER_SECOND = 1000
const HEADROOM = 1.15
const MIN_TOP = 10

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
      {lines.map((l) => (
        <polyline
          key={l.name}
          points={l.points
            .filter((p): p is [number, number] => p[1] !== null)
            .map(([s, v]) => `${x(s)},${y(v)}`)
            .join(' ')}
          fill="none"
          stroke={l.color}
          strokeWidth={2.2}
          strokeLinejoin="round"
        />
      ))}
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

/** A crash lasts until the first second after it in which the ledger booked again. */
function crashBands(chaos: ChaosInRun[], booked: Point[], lastSecond: number): [number, number][] {
  return chaos.map((c) => {
    const from = c.offsetMs / MS_PER_SECOND
    const back = booked.find(([s, v]) => s > from + 1 && v !== null && v > 0)
    return [from, back?.[0] ?? lastSecond]
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
  rateLines: RateLines
  onRateLines: (lines: RateLines) => void
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
  const rate =
    props.rateLines === 'accepted'
      ? [acceptedLine, bookedLine]
      : props.rateLines === 'clients'
        ? [{ ...clientsLine, color: 'var(--flow)' }, bookedLine]
        : [clientsLine, acceptedLine, bookedLine]
  const latency: Line[] = [
    { name: 'median (p50)', color: 'var(--flow)', points: p50 },
    { name: 'slowest 1% (p99)', color: 'var(--warn)', points: p99 },
  ]

  return (
    <section className="charts">
      <div className="card chart">
        <h3>Transfers per second</h3>
        <div className="probe">
          probe:{' '}
          {(Object.keys(RATE_LINES) as RateLines[]).map((k) => (
            <label key={k}>
              <input type="radio" name="rateLines" checked={props.rateLines === k} onChange={() => props.onRateLines(k)} />{' '}
              {RATE_LINES[k]}
            </label>
          ))}
        </div>
        <Legend lines={rate} />
        <Chart lines={rate} maxSecond={maxSecond} bands={bands} />
      </div>
      <div className="card chart">
        <h3>Time until the bank's answer, ms</h3>
        <Legend lines={latency} />
        <Chart lines={latency} maxSecond={maxSecond} bands={bands} />
      </div>
    </section>
  )
}
