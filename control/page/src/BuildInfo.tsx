import { AUTHOR_NAME, AUTHOR_URL } from './author'

declare const __BUILD_COMMIT__: string
declare const __BUILD_AT__: string

export function BuildInfo() {
  return (
    <footer className="build">
      by{' '}
      <a href={AUTHOR_URL} target="_blank" rel="noopener">
        {AUTHOR_NAME}
      </a>{' '}
      · build {__BUILD_COMMIT__} · {new Date(__BUILD_AT__).toLocaleString()}
    </footer>
  )
}
