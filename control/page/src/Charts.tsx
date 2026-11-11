import type { ChaosEvent, SeriesName, SeriesView } from './api/client'
import { count, hoursMinutes, MS_PER_SECOND } from './format'
import { t } from './texts'

type Point = [number, number | null]
type Line = { name: SeriesName; color: string; points: Point[] }

const W = 420
const H = 170
const LEFT = 44
const BOTTOM = 22
const TOP = 8
const HEADROOM = 1.15
const MIN_TOP = 10
const SECONDS_PER_HOUR = 3600

/** A point without a value breaks the line: the gap is drawn as a gap, not as 0. */
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

export function seriesPoints(view: SeriesView, name: SeriesName): Point[] {
  const values = view.series.find((s) => s.name === name)?.values ?? []
  return values.map((v, i) => [i * view.stepSeconds, v])
}

export function crashBands(chaos: ChaosEvent[], view: SeriesView): [number, number][] {
  const from = Date.parse(view.from)
  const span = (view.points - 1) * view.stepSeconds
  return chaos
    .map((c) => (Date.parse(c.at) - from) / MS_PER_SECOND)
    .filter((s) => s >= 0 && s <= span)
    .map((s) => [s, Math.min(span, s + view.stepSeconds)])
}

function Chart({ lines, span, bands, edges }: { lines: Line[]; span: number; bands: [number, number][]; edges: [string, string] }) {
  const values = lines.flatMap((l) => l.points.map(([, v]) => v).filter((v): v is number => v !== null))
  const top = Math.max(MIN_TOP, ...values) * HEADROOM
  const x = (s: number) => LEFT + (W - LEFT - 8) * (s / Math.max(span, 1))
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
        {edges[0]}
      </text>
      <text x={W - 8} y={H - 4} fontSize={10} fill="var(--muted)" textAnchor="end">
        {edges[1]}
      </text>
      {bands.map(([from, to]) => (
        <g key={from} data-testid="crash-band">
          <rect x={x(from)} y={TOP} width={Math.max(2, x(to) - x(from))} height={H - TOP - BOTTOM} fill="var(--bad)" opacity={0.12} />
          <text x={x(from) + 3} y={TOP + 12} fontSize={10} fill="var(--bad)">
            {t.charts.crashed}
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
          {t.charts.series[l.name]}
        </span>
      ))}
    </div>
  )
}

export function Charts({ view, chaos }: { view: SeriesView | null; chaos: ChaosEvent[] }) {
  if (view === null) return null
  const span = Math.max(0, (view.points - 1) * view.stepSeconds)
  const line = (name: SeriesName, color: string): Line => ({ name, color, points: seriesPoints(view, name) })
  const rate = [
    line('ACCEPTED_PER_SECOND', 'var(--flow)'),
    line('POSTED_PER_SECOND', 'var(--ok)'),
    line('REJECTED_PER_SECOND', 'var(--bad)'),
    line('BUSY_PER_SECOND', 'var(--warn)'),
  ]
  const latency = [line('BANK_LATENCY_P50_MS', 'var(--flow)'), line('BANK_LATENCY_P99_MS', 'var(--warn)')]
  const from = Date.parse(view.from)
  const edges: [string, string] = [
    hoursMinutes(view.from),
    hoursMinutes(new Date(from + span * MS_PER_SECOND).toISOString()),
  ]
  const bands = crashBands(chaos, view)
  return (
    <section className="charts" aria-label={t.charts.rate(Math.round(span / SECONDS_PER_HOUR))}>
      <div className="card chart">
        <h3>{t.charts.rate(Math.round(span / SECONDS_PER_HOUR))}</h3>
        <Legend lines={rate} />
        {view.series.length === 0 && <p className="hint">{t.charts.noData}</p>}
        <Chart lines={rate} span={span} bands={bands} edges={edges} />
      </div>
      <div className="card chart">
        <h3>{t.charts.latency}</h3>
        <Legend lines={latency} />
        <Chart lines={latency} span={span} bands={bands} edges={edges} />
      </div>
    </section>
  )
}
