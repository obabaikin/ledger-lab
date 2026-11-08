import { Link } from 'react-router'
import { AUTHOR_NAME, AUTHOR_URL } from './author'
import { t } from './texts'
import { useSite } from './useSite'

export function BuildInfo() {
  const site = useSite()
  return (
    <footer className="build">
      by{' '}
      <a href={AUTHOR_URL} target="_blank" rel="noopener">
        {AUTHOR_NAME}
      </a>{' '}
      · {site && `${site.version} · `}<Link to="/privacy">{t.footer.privacy}</Link> ·{' '}
      <Link to="/impressum">{t.footer.impressum}</Link>
    </footer>
  )
}
