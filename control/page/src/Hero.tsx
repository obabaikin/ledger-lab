import type { ReactNode } from 'react'
import type { BankView } from './api/client'
import { REPOSITORY_URL } from './Diagram'
import { clock, rate } from './format'
import { clientsSpeed } from './speed'
import { t } from './texts'

const MS_PER_MINUTE = 60_000
const HOW_IT_WORKS_URL = `${REPOSITORY_URL}#what-it-does`

const MENU: { key: keyof typeof t.hero.menu; href: string }[] = [
  { key: 'howItWorks', href: HOW_IT_WORKS_URL },
  { key: 'designDecisions', href: `${REPOSITORY_URL}/tree/main/docs/adr` },
  { key: 'runLocally', href: `${REPOSITORY_URL}#running-locally` },
  { key: 'tests', href: `${REPOSITORY_URL}/tree/main/control/src/test` },
  { key: 'code', href: REPOSITORY_URL },
]

/** Rounded up and at least 1: an answer read a moment before the change still says it is to come. */
const minutesUntil = (iso: string, now: number) => Math.max(1, Math.ceil((Date.parse(iso) - now) / MS_PER_MINUTE))

function loadLine(bank: BankView | null): string | null {
  const pulse = bank?.clients?.pulse
  if (bank?.clients && pulse) return t.hero.pulse(rate(pulse.tps), clock(pulse.until), rate(bank.clients.baseTps))
  const load = bank?.load
  if (!load) return null
  return load.nextChangeAt === null
    ? t.hero.base(rate(load.baseTps))
    : t.hero.baseChangesIn(rate(load.baseTps), minutesUntil(load.nextChangeAt, Date.now()))
}

export function TopBar() {
  return (
    <div className="topbar">
      <span className="brand">{t.hero.brand}</span>
      <nav className="links">
        {MENU.map((item) => (
          <a key={item.key} href={item.href} target="_blank" rel="noopener">
            {t.hero.menu[item.key]}
          </a>
        ))}
      </nav>
    </div>
  )
}

export function Hero(props: { bank: BankView | null; action?: ReactNode }) {
  const speed = clientsSpeed(props.bank?.clients ?? null)
  const load = loadLine(props.bank)
  return (
    <header className="hero">
      <TopBar />
      <h1>
        {speed === null ? (
          t.inviteForm.heading
        ) : (
          <>
            {t.hero.before}{' '}
            <span className={`liveRate ${speed.pulse ? 'warn' : 'ok'}`} data-testid="hero-rate">
              <span className="rate"><i className="dot blink" /> {rate(speed.tps)}</span> {t.hero.unit}
            </span>{' '}
            {t.hero.after}
          </>
        )}
      </h1>
      <p className="heroSub" data-testid="hero-sub">
        {load === null ? t.hero.reconciled : `${load} ${t.hero.reconciled}`}
      </p>
      <div className="heroActions">
        {props.action}
        <a className="more" href={HOW_IT_WORKS_URL} target="_blank" rel="noopener">
          {t.hero.howItWorks}
        </a>
      </div>
    </header>
  )
}
