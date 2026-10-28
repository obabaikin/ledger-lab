import { Fragment } from 'react'
import { Link } from 'react-router'
import { BuildInfo } from './BuildInfo'
import { Hero } from './Hero'
import { t } from './texts'
import { useSite } from './useSite'
import type { SiteView } from './api/client'

function Operator(props: { site: SiteView | null }) {
  const operator = props.site?.operator
  const notSet = t.operator.notSet
  return (
    <address>
      {operator?.name ?? notSet}
      <br />
      {operator?.address ?? notSet}
      <br />
      {operator ? <a href={`mailto:${operator.email}`}>{operator.email}</a> : notSet}
    </address>
  )
}

const PRIVACY_SECTIONS = [
  [t.privacy.heading1, t.privacy.text1],
  [t.privacy.heading2, t.privacy.text2],
  [t.privacy.heading3, t.privacy.text3],
  [t.privacy.heading4, t.privacy.text4],
  [t.privacy.heading5, t.privacy.text5],
  [t.privacy.heading6, t.privacy.text6],
  [t.privacy.heading7, t.privacy.text7],
]

export function Privacy() {
  const site = useSite()
  return (
    <main>
      <Hero />
      <section className="card legal">
        <h2>{t.privacy.title}</h2>
        {PRIVACY_SECTIONS.map(([heading, text]) => (
          <Fragment key={heading}>
            <h3>{heading}</h3>
            <p>{text}</p>
          </Fragment>
        ))}
        <Operator site={site} />
        <p>
          <Link to="/">{t.privacy.backToStart}</Link>
        </p>
      </section>
      <BuildInfo />
    </main>
  )
}

export function Impressum() {
  const site = useSite()
  return (
    <main>
      <Hero />
      <section className="card legal">
        <h2>{t.impressum.title}</h2>
        <p>{t.impressum.responsible}</p>
        <Operator site={site} />
        <p>
          <Link to="/">{t.impressum.backToStart}</Link>
        </p>
      </section>
      <BuildInfo />
    </main>
  )
}
