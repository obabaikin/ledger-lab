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

export function Privacy() {
  const site = useSite()
  const p = t.privacy
  const sections = [
    [p.heading1, p.text1],
    [p.heading2, p.text2],
    [p.heading3, site ? `${p.text3(site.retentionDays)} ${p.text3Remains}` : p.text3Remains],
    [p.heading4, p.text4],
    [p.heading5, p.text5],
    [p.heading6, p.text6],
    [p.heading7, p.text7],
  ]
  return (
    <main>
      <Hero />
      <section className="card legal">
        <h2>{t.privacy.title}</h2>
        {sections.map(([heading, text]) => (
          <Fragment key={heading}>
            <h3>{heading}</h3>
            <p>{text}</p>
          </Fragment>
        ))}
        <Operator site={site} />
        <p lang="de">
          {p.generatorBefore}
          <a href={p.generatorUrl} target="_blank" rel="noopener noreferrer">
            {p.generatorLink}
          </a>
          {p.generatorAfter}
        </p>
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
