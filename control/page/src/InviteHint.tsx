const AUTHOR_URL = 'https://www.linkedin.com/in/oleksii-babaikin'

export function InviteHint() {
  return (
    <span className="hint">
      To start your own run, ask the author for an invite on{' '}
      <a href={AUTHOR_URL} target="_blank" rel="noopener">
        LinkedIn
      </a>
      .
    </span>
  )
}
