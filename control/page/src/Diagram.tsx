import type { PodView } from './api/client'
import { FlowDots } from './FlowDots'
import { count, shown } from './format'
import { inFlight } from './shownCounts'

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
  clients: { box: [10, 100, 140, 96], title: 'Clients', sub: 'simulated customers', code: 'generator', job: null },
  gateway: { box: [250, 100, 150, 96], title: 'Gateway', sub: 'HTTP API · outbox', code: 'gateway', job: 'gateway' },
  kafka: { box: [500, 100, 120, 96], title: 'Kafka', sub: 'waiting to be booked', code: 'common', job: null },
  ledger: { box: [700, 100, 160, 96], title: 'Ledger', sub: 'double-entry books', code: 'ledger-service', job: 'ledger' },
} as const

const WIRES = [
  ['M150 135 L250 135', false],
  ['M400 135 L500 135', false],
  ['M620 135 L700 135', false],
  ['M700 160 L620 160', true],
  ['M780 196 L780 240', false],
  ['M500 160 L400 160', true],
  ['M250 160 L150 160', true],
] as const

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
  return (
    <svg id="diagram" viewBox="0 0 900 300" role="img" aria-label="Transfers flowing through the bank">
      <rect x={205} y={40} width={685} height={252} rx={14} className="bank" />
      <text className="zone" x={220} y={60}>
        THE BANK
      </text>
      <text className="zone" x={80} y={60} textAnchor="middle">
        OUTSIDE
      </text>
      {WIRES.map(([d, back]) => (
        <path key={d} className={back ? 'wire back' : 'wire'} d={d} />
      ))}
      <text className="label" x={200} y={126} textAnchor="middle">
        public API
      </text>
      <text className="label" x={450} y={128} textAnchor="middle" style={{ fill: 'var(--flow)' }}>
        transfers →
      </text>
      <text className="label" x={450} y={176} textAnchor="middle" style={{ fill: 'var(--ok)' }}>
        ← results
      </text>
      <text className="label" x={200} y={178} textAnchor="middle">
        accepted · final
      </text>
      {Object.entries(NODES).map(([key, node]) => {
        const [x, y, w, h] = node.box
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
      {rates && <FlowDots rates={rates} />}
      <rect x={715} y={240} width={130} height={40} rx={8} className="bin" />
      <text x={780} y={257} textAnchor="middle" className="binLabel">
        rejected by the rules
      </text>
      <text x={780} y={273} textAnchor="middle" className="num binNum">
        {bare ? '' : shown(rejected)}
      </text>
    </svg>
  )
}
