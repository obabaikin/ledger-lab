import { Link } from 'react-router'
import { AUTHOR_NAME, AUTHOR_URL } from './author'
import { t } from './texts'

declare const __BUILD_VERSION__: string
declare const __BUILD_AT__: string

export function BuildInfo() {
  return (
    <footer className="build">
      by{' '}
      <a href={AUTHOR_URL} target="_blank" rel="noopener">
        {AUTHOR_NAME}
      </a>{' '}
      · {__BUILD_VERSION__} · {new Date(__BUILD_AT__).toLocaleString()} · <Link to="/privacy">{t.footer.privacy}</Link> ·{' '}
      <Link to="/impressum">{t.footer.impressum}</Link>
    </footer>
  )
}
