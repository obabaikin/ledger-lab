import type { PodView } from './api/client'
import { FlowDots } from './FlowDots'
import { count, shown } from './format'
import { inFlight } from './shownCounts'
import { useNarrow } from './useNarrow'

export const REPOSITORY_URL = 'https://github.com/obabaikin/ledger-lab'
const CODE_URL = `${REPOSITORY_URL}/tree/main/`

export type DiagramNumbers = {
  requested: number | null
  accepted: number | null
  posted: number | null
  rejected: number | null
  ledgerDuplicatesAtLeast: number | null
  duplicatesSent: number | null
  refused: number | null
}

export const NO_NUMBERS: DiagramNumbers = {
  requested: null,
  accepted: null,
  posted: null,
  rejected: null,
  ledgerDuplicatesAtLeast: null,
  duplicatesSent: null,
  refused: null,
}

type PodState = 'consuming' | 'starting' | 'down' | 'unknown'

const POD_COLOR: Record<PodState, string> = {
  consuming: 'var(--ok)',
  starting: 'var(--warn)',
  down: 'var(--bad)',
  unknown: 'var(--muted)',
}

const POD_LEGEND: Record<PodState, string> = {
  consuming: 'consuming',
  starting: 'starting',
  down: 'no answer to Prometheus',
  unknown: 'no data',
}

export function podState(pod: PodView): PodState {
  if (!pod.up) return 'down'
  if (pod.partitions === 0) return 'starting'
  return 'consuming'
}

export function podStates(pods: PodView[] | null): { name: string; state: PodState }[] {
  return pods === null ? [{ name: 'no data', state: 'unknown' }] : pods.map((p) => ({ name: p.instance, state: podState(p) }))
}

export function PodLegend() {
  return (
    <div className="legend podLegend" aria-label="Instances">
      {(Object.keys(POD_LEGEND) as PodState[]).map((state) => (
        <span key={state}>
          <i className="dot" style={{ background: POD_COLOR[state] }} />
          {POD_LEGEND[state]}
        </span>
      ))}
    </div>
  )
}

const NODES = {
  clients: { title: 'Clients', sub: 'simulated customers', code: 'generator', job: null },
  gateway: { title: 'Gateway', sub: 'HTTP API · outbox', code: 'gateway', job: 'gateway' },
  kafka: { title: 'Kafka', sub: 'waiting to be booked', code: 'common', job: null },
  ledger: { title: 'Ledger', sub: 'double-entry books', code: 'ledger-service', job: 'ledger' },
} as const

type Box = readonly [x: number, y: number, w: number, h: number]
type Place = { x: number; y: number; anchor: 'start' | 'middle' | 'end' }

type Layout = {
  width: number
  height: number
  bank: Box
  bankZone: Place
  outsideZone: Place
  boxes: Record<keyof typeof NODES, Box>
  /** In the order of FlowDots' rates. */
  wires: readonly (readonly [d: string, back: boolean])[]
  labels: Record<'api' | 'transfers' | 'results' | 'final', Place>
  arrows: { forward: string; back: string }
  bin: Box
}

const ACROSS: Layout = {
  width: 900,
  height: 300,
  bank: [205, 40, 685, 252],
  bankZone: { x: 220, y: 60, anchor: 'start' },
  outsideZone: { x: 80, y: 60, anchor: 'middle' },
  boxes: {
    clients: [10, 100, 140, 96],
    gateway: [250, 100, 150, 96],
    kafka: [500, 100, 120, 96],
    ledger: [700, 100, 160, 96],
  },
  wires: [
    ['M150 135 L250 135', false],
    ['M400 135 L500 135', false],
    ['M620 135 L700 135', false],
    ['M700 160 L620 160', true],
    ['M780 196 L780 240', false],
    ['M500 160 L400 160', true],
    ['M250 160 L150 160', true],
  ],
  labels: {
    api: { x: 200, y: 126, anchor: 'middle' },
    transfers: { x: 450, y: 128, anchor: 'middle' },
    results: { x: 450, y: 176, anchor: 'middle' },
    final: { x: 200, y: 178, anchor: 'middle' },
  },
  arrows: { forward: '→', back: '←' },
  bin: [715, 240, 130, 40],
}

const UPRIGHT: Layout = {
  width: 360,
  height: 690,
  bank: [10, 158, 340, 522],
  bankZone: { x: 24, y: 176, anchor: 'start' },
  outsideZone: { x: 180, y: 18, anchor: 'middle' },
  boxes: {
    clients: [95, 28, 170, 96],
    gateway: [95, 196, 170, 96],
    kafka: [110, 346, 140, 96],
    ledger: [95, 496, 170, 96],
  },
  wires: [
    ['M150 124 L150 196', false],
    ['M150 292 L150 346', false],
    ['M150 442 L150 496', false],
    ['M210 496 L210 442', true],
    ['M180 592 L180 626', false],
    ['M210 346 L210 292', true],
    ['M210 196 L210 124', true],
  ],
  labels: {
    api: { x: 144, y: 148, anchor: 'end' },
    transfers: { x: 144, y: 323, anchor: 'end' },
    results: { x: 216, y: 323, anchor: 'start' },
    final: { x: 216, y: 148, anchor: 'start' },
  },
  arrows: { forward: '↓', back: '↑' },
  bin: [115, 626, 130, 40],
}

function Pods({ x, y, w, pods }: { x: number; y: number; w: number; pods: PodView[] | null }) {
  const states = podStates(pods)
  return (
    <g>
      {states.map((p, i) => (
        <circle
          key={p.name}
          cx={x + w / 2 + (i - (states.length - 1) / 2) * 14}
          cy={y - 10}
          r={5}
          fill={POD_COLOR[p.state]}
          data-testid="pod"
          data-state={p.state}
        >
          <title>{`${p.name}: ${POD_LEGEND[p.state]}`}</title>
        </circle>
      ))}
    </g>
  )
}

/** Without numbers (null) the diagram only shows the way a transfer takes, as for a visitor without an invite. */
export function Diagram(props: {
  numbers: DiagramNumbers | null
  pods: PodView[] | null | undefined
  rates?: number[]
  /** The ledger's look where no pods tell it, as in the illustration. */
  ledgerLook?: 'dead' | 'recovering' | null
}) {
  const { pods, rates } = props
  const numbers = props.numbers ?? NO_NUMBERS
  const bare = props.numbers === null
  const { requested, accepted, posted, rejected } = numbers
  const waiting = accepted === null || posted === null || rejected === null ? null : inFlight({ accepted, posted, rejected })
  const ledgerPods = pods === undefined ? undefined : (pods?.filter((p) => p.job === 'ledger') ?? null)
  const consuming = ledgerPods?.filter((p) => podState(p) === 'consuming').length
  const ledgerClass =
    ledgerPods == null || ledgerPods.length === 0
      ? (props.ledgerLook ?? '')
      : consuming === 0
        ? 'dead'
        : consuming! < ledgerPods.length
          ? 'degraded'
          : ''
  const lines: Record<string, [string, string, string]> = bare ? {} : {
    clients: [`${shown(requested)} sent`, 'their own count', numbers.duplicatesSent ? `↺ ${count(numbers.duplicatesSent)} pressed twice` : ''],
    gateway: [`${shown(accepted)} accepted`, numbers.refused === null ? '' : `${count(numbers.refused)} refused (503)`, ''],
    ledger: [
      `${shown(posted)} booked`,
      ledgerPods ? `${consuming} of ${ledgerPods.length} consuming` : '',
      numbers.ledgerDuplicatesAtLeast ? `↺ ≥ ${count(numbers.ledgerDuplicatesAtLeast)} redelivered, skipped` : '',
    ],
  }
  const layout = useNarrow() ? UPRIGHT : ACROSS
  const { labels, arrows } = layout
  const [binX, binY, binW, binH] = layout.bin
  return (
    <svg id="diagram" viewBox={`0 0 ${layout.width} ${layout.height}`} role="img" aria-label="Transfers flowing through the bank">
      <rect x={layout.bank[0]} y={layout.bank[1]} width={layout.bank[2]} height={layout.bank[3]} rx={14} className="bank" />
      <text className="zone" x={layout.bankZone.x} y={layout.bankZone.y} textAnchor={layout.bankZone.anchor}>
        THE BANK
      </text>
      <text className="zone" x={layout.outsideZone.x} y={layout.outsideZone.y} textAnchor={layout.outsideZone.anchor}>
        OUTSIDE
      </text>
      {layout.wires.map(([d, back]) => (
        <path key={d} className={back ? 'wire back' : 'wire'} d={d} />
      ))}
      <text className="label" x={labels.api.x} y={labels.api.y} textAnchor={labels.api.anchor}>
        public API
      </text>
      <text className="label" x={labels.transfers.x} y={labels.transfers.y} textAnchor={labels.transfers.anchor} style={{ fill: 'var(--flow)' }}>
        transfers {arrows.forward}
      </text>
      <text className="label" x={labels.results.x} y={labels.results.y} textAnchor={labels.results.anchor} style={{ fill: 'var(--ok)' }}>
        {arrows.back} results
      </text>
      <text className="label" x={labels.final.x} y={labels.final.y} textAnchor={labels.final.anchor}>
        accepted · final
      </text>
      {(Object.keys(NODES) as (keyof typeof NODES)[]).map((key) => {
        const node = NODES[key]
        const [x, y, w, h] = layout.boxes[key]
        const cx = x + w / 2
        const [num, note, repeats] = lines[key] ?? ['', '', '']
        return (
          <a key={key} className={`node ${key === 'ledger' ? ledgerClass : ''}`} href={CODE_URL + node.code} target="_blank" rel="noopener">
            <title>{`${node.title}: open the code on GitHub`}</title>
            <rect className="box" x={x} y={y} width={w} height={h} rx={10} />
            <text className="title" x={cx} y={y + 22} textAnchor="middle">
              {node.title} ↗
            </text>
            <text className="sub" x={cx} y={y + 37} textAnchor="middle">
              {node.sub}
            </text>
            {key === 'kafka' ? (
              <>
                <text className="num" x={cx} y={y + 61} textAnchor="middle" data-testid="in-flight">
                  {bare ? '' : shown(waiting)}
                </text>
                <text className="sub" x={cx} y={y + 84} textAnchor="middle">
                  in flight
                </text>
              </>
            ) : (
              <>
                <text className="num" x={cx} y={y + 58} textAnchor="middle" data-testid={`${key}-number`}>
                  {num}
                </text>
                <text className={key === 'gateway' && (numbers.refused ?? 0) > 0 ? 'sub busy' : 'sub'} x={cx} y={y + 73} textAnchor="middle" data-testid={`${key}-note`}>
                  {note}
                </text>
                <text className="repeats" x={cx} y={y + 89} textAnchor="middle">
                  {repeats}
                </text>
              </>
            )}
            {node.job !== null && pods !== undefined && (
              <Pods x={x} y={y} w={w} pods={pods === null ? null : pods.filter((p) => p.job === node.job)} />
            )}
          </a>
        )
      })}
      {/* FlowDots reads the wires once: a new layout brings new wires, so it starts again. */}
      {rates && <FlowDots key={layout.width} rates={rates} />}
      <rect x={binX} y={binY} width={binW} height={binH} rx={8} className="bin" />
      <text x={binX + binW / 2} y={binY + 17} textAnchor="middle" className="binLabel">
        rejected by the rules
      </text>
      <text x={binX + binW / 2} y={binY + 33} textAnchor="middle" className="num binNum">
        {bare ? '' : shown(rejected)}
      </text>
    </svg>
  )
}
