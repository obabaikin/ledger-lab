import { REPOSITORY_URL } from './Diagram'

export function Hero() {
  return (
    <header className="hero">
      <h1 className="brand">Ledger Lab</h1>
      <nav className="links">
        <a href={`${REPOSITORY_URL}#what-it-does`} target="_blank" rel="noopener">
          How it works
        </a>
        <a href={`${REPOSITORY_URL}/tree/main/docs/adr`} target="_blank" rel="noopener">
          Design decisions
        </a>
        <a href={`${REPOSITORY_URL}#running-locally`} target="_blank" rel="noopener">
          Run locally
        </a>
        <a href={`${REPOSITORY_URL}/tree/main/control/src/test`} target="_blank" rel="noopener">
          Tests
        </a>
        <a href={REPOSITORY_URL} target="_blank" rel="noopener">
          Code
        </a>
      </nav>
    </header>
  )
}
