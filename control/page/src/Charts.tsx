import { useState } from 'react'
import { CHART_RANGES, type ChartRange, type ChaosEvent, type SeriesName, type SeriesView } from './api/client'
import { count, hoursMinutes, MS_PER_SECOND, NO_DATA } from './format'
import { t } from './texts'

type Point = [number, number | null]
type Line = { name: SeriesName; color: string; points: Point[] }
type Guide = { name: keyof typeof t.charts.guides; value: number; color: string }
type Scale = { y: (v: number) => number | null; ticks: { value: number; label: string }[] }

const W = 420
const H = 170
const SMALL_H = 110
const LEFT = 44
const RIGHT = 8
const BOTTOM = 22
const TOP = 8
const HEADROOM = 1.15
const MIN_TOP = 10
const LINEAR_TICKS = 3
/** The latency's log scale, in milliseconds: from below the fastest transfers to the gateway's longest bucket. */
const LOG_FLOOR_MS = 5
const LOG_CEILING_MS = 60_000
const LOG_TICKS_MS = [10, 100, 1000, 10_000]
/** Where the latency lines ought to stay: guides only, not objectives. */
const P50_GUIDE_MS = 150
const P99_GUIDE_MS = 500

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

/** The grid point nearest to a place on the chart, as a share of the plotted width from its left edge. */
export function nearestPoint(share: number, points: number): number {
  return Math.min(points - 1, Math.max(0, Math.round(share * (points - 1))))
}

function linearScale(lines: Line[], height: number): Scale {
  const values = lines.flatMap((l) => l.points.map(([, v]) => v).filter((v): v is number => v !== null))
  const top = Math.max(MIN_TOP, ...values) * HEADROOM
  const ticks = Array.from({ length: LINEAR_TICKS + 1 }, (_, g) => (top * g) / LINEAR_TICKS)
  return {
    y: (v) => TOP + (height - TOP - BOTTOM) * (1 - v / top),
    ticks: ticks.map((value) => ({ value, label: count(value) })),
  }
}

/** A value of 0 has no place on a log scale: it breaks the line as a missing one does. */
export function logY(v: number, height: number): number | null {
  if (v <= 0) return null
  const share = (Math.log10(Math.min(Math.max(v, LOG_FLOOR_MS), LOG_CEILING_MS)) - Math.log10(LOG_FLOOR_MS)) / Math.log10(LOG_CEILING_MS / LOG_FLOOR_MS)
  return TOP + (height - TOP - BOTTOM) * (1 - share)
}

function logScale(height: number): Scale {
  return {
    y: (v) => logY(v, height),
    ticks: LOG_TICKS_MS.map((value) => ({ value, label: duration(value) })),
  }
}

const SECONDS_SHOWN = { maximumFractionDigits: 1 }

function duration(ms: number): string {
  return ms < MS_PER_SECOND ? t.charts.milliseconds(count(ms)) : t.charts.seconds((ms / MS_PER_SECOND).toLocaleString('en-US', SECONDS_SHOWN))
}

function Chart(props: {
  lines: Line[]
  span: number
  bands: [number, number][]
  edges: [string, string]
  scale: Scale
  height: number
  guides?: Guide[]
  at: number | null
  onAt: (share: number | null) => void
  label: string
}) {
  const { lines, span, bands, edges, scale, height, guides = [], at, onAt } = props
  const x = (s: number) => LEFT + (W - LEFT - RIGHT) * (s / Math.max(span, 1))
  const shareAt = (svg: SVGSVGElement, clientX: number) => {
    const box = svg.getBoundingClientRect()
    const plotted = ((clientX - box.left) / Math.max(box.width, 1)) * W
    return (plotted - LEFT) / (W - LEFT - RIGHT)
  }
  const atSeconds = at === null ? null : lines[0]?.points[at]?.[0] ?? null
  return (
    <svg
      viewBox={`0 0 ${W} ${height}`}
      role="img"
      aria-label={props.label}
      onMouseMove={(e) => onAt(shareAt(e.currentTarget, e.clientX))}
      onMouseLeave={() => onAt(null)}
      onTouchStart={(e) => {
        const touch = e.touches[0]
        if (touch) onAt(shareAt(e.currentTarget, touch.clientX))
      }}
    >
      {scale.ticks.map(({ value, label }) => {
        const y = scale.y(value)
        return y === null ? null : (
          <g key={value}>
            <line x1={LEFT} x2={W - RIGHT} y1={y} y2={y} stroke="var(--line)" />
            <text x={LEFT - 6} y={y + 4} textAnchor="end" fontSize={10} fill="var(--muted)">
              {label}
            </text>
          </g>
        )
      })}
      <text x={LEFT} y={height - 4} fontSize={10} fill="var(--muted)">
        {edges[0]}
      </text>
      <text x={W - RIGHT} y={height - 4} fontSize={10} fill="var(--muted)" textAnchor="end">
        {edges[1]}
      </text>
      {guides.map((g) => {
        const y = scale.y(g.value)
        return y === null ? null : (
          <g key={g.name} data-testid="latency-guide">
            <line x1={LEFT} x2={W - RIGHT} y1={y} y2={y} stroke={g.color} strokeDasharray="4 3" opacity={0.8} />
            <text x={W - RIGHT} y={y - 3} fontSize={10} fill={g.color} textAnchor="end">
              {t.charts.guides[g.name](g.value)}
            </text>
          </g>
        )
      })}
      {bands.map(([from, to]) => (
        <g key={from} data-testid="crash-band">
          <rect x={x(from)} y={TOP} width={Math.max(2, x(to) - x(from))} height={height - TOP - BOTTOM} fill="var(--bad)" opacity={0.12} />
          <text x={x(from) + 3} y={TOP + 12} fontSize={10} fill="var(--bad)">
            {t.charts.crashed}
          </text>
        </g>
      ))}
      {lines.flatMap((l) =>
        segments(l.points.map(([s, v]): Point => [s, v === null ? null : scale.y(v)])).map((segment) =>
          segment.length === 1 ? (
            <circle key={`${l.name}-${segment[0]![0]}`} cx={x(segment[0]![0])} cy={segment[0]![1]} r={1.8} fill={l.color} />
          ) : (
            <polyline
              key={`${l.name}-${segment[0]![0]}`}
              data-testid={`line-${l.name}`}
              points={segment.map(([s, y]) => `${x(s)},${y}`).join(' ')}
              fill="none"
              stroke={l.color}
              strokeWidth={2.2}
              strokeLinejoin="round"
            />
          ),
        ),
      )}
      {atSeconds !== null && <line data-testid="chart-cursor" x1={x(atSeconds)} x2={x(atSeconds)} y1={TOP} y2={height - BOTTOM} stroke="var(--muted)" />}
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

function Readout({ lines, at, from, step, as }: { lines: Line[]; at: number; from: number; step: number; as: (v: number) => string }) {
  return (
    <p className="readout" data-testid="chart-readout">
      {hoursMinutes(new Date(from + at * step * MS_PER_SECOND).toISOString())}
      {lines.map((l) => {
        const v = l.points[at]?.[1] ?? null
        return <span key={l.name}>{` · ${t.charts.series[l.name]} ${v === null ? NO_DATA : as(v)}`}</span>
      })}
    </p>
  )
}

export function Charts(props: { view: SeriesView | null; chaos: ChaosEvent[]; range: ChartRange; onRange: (range: ChartRange) => void }) {
  const { view, chaos, range, onRange } = props
  const [at, setAt] = useState<number | null>(null)
  const picker = (
    <label className="rangePicker">
      {t.charts.rangeLabel}{' '}
      <select value={range} onChange={(e) => onRange(CHART_RANGES.find((r) => r === e.target.value) ?? range)}>
        {CHART_RANGES.map((r) => (
          <option key={r} value={r}>
            {t.charts.ranges[r]}
          </option>
        ))}
      </select>
    </label>
  )
  if (view === null) return <section className="charts">{picker}</section>
  const span = Math.max(0, (view.points - 1) * view.stepSeconds)
  const line = (name: SeriesName, color: string): Line => ({ name, color, points: seriesPoints(view, name) })
  const flow = [line('ACCEPTED_PER_SECOND', 'var(--flow)'), line('POSTED_PER_SECOND', 'var(--ok)')]
  const refused = [line('REJECTED_PER_SECOND', 'var(--bad)'), line('BUSY_PER_SECOND', 'var(--warn)')]
  const latency = [line('BANK_LATENCY_P50_MS', 'var(--flow)'), line('BANK_LATENCY_P99_MS', 'var(--warn)')]
  const guides: Guide[] = [
    { name: 'BANK_LATENCY_P50_MS', value: P50_GUIDE_MS, color: 'var(--flow)' },
    { name: 'BANK_LATENCY_P99_MS', value: P99_GUIDE_MS, color: 'var(--warn)' },
  ]
  const from = Date.parse(view.from)
  const edges: [string, string] = [hoursMinutes(view.from), hoursMinutes(new Date(from + span * MS_PER_SECOND).toISOString())]
  const bands = crashBands(chaos, view)
  const point = at === null ? null : nearestPoint(at, view.points)
  const onAt = (share: number | null) => setAt(share)
  const common = { span, bands, edges, at: point, onAt }
  const readout = (lines: Line[], as: (v: number) => string) =>
    point !== null && <Readout lines={lines} at={point} from={from} step={view.stepSeconds} as={as} />
  return (
    <section className="charts" aria-label={t.charts.flow}>
      <div className="chartsHead">{picker}</div>
      <div className="card chart">
        <h3>{t.charts.flow}</h3>
        <Legend lines={flow} />
        {view.series.length === 0 && <p className="hint">{t.charts.noData}</p>}
        <Chart {...common} lines={flow} scale={linearScale(flow, H)} height={H} label={t.charts.flow} />
        {readout(flow, count)}
      </div>
      <div className="card chart">
        <h3>{t.charts.latency}</h3>
        <Legend lines={latency} />
        <Chart {...common} lines={latency} scale={logScale(H)} height={H} guides={guides} label={t.charts.latency} />
        {readout(latency, duration)}
      </div>
      <div className="card chart">
        <h3>{t.charts.refused}</h3>
        <Legend lines={refused} />
        <Chart {...common} lines={refused} scale={linearScale(refused, SMALL_H)} height={SMALL_H} label={t.charts.refused} />
        {readout(refused, count)}
      </div>
    </section>
  )
}
