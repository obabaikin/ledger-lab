import { useEffect, useRef, useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router'
import { api } from './api/client'
import { Diagram } from './Diagram'
import { BuildInfo } from './BuildInfo'
import { Hero } from './Hero'

export function Landing() {
  const [params] = useSearchParams()
  const token = params.get('ref')
  const navigate = useNavigate()
  const [inviteRefused, setInviteRefused] = useState(false)
  // Each session opened is a visit counted for the invite, so a remount must not open a second one.
  const opening = useRef<Promise<string> | null>(null)

  useEffect(() => {
    if (token === null) return
    opening.current ??= api.openSession(token).then(({ sessionId }) => sessionId)
    let current = true
    opening.current
      .then((sessionId) => current && navigate(`/s/${sessionId}`, { replace: true }))
      .catch(() => current && setInviteRefused(true))
    return () => {
      current = false
    }
  }, [token, navigate])

  if (token !== null && !inviteRefused) return <main aria-busy="true" />

  return (
    <main>
      <Hero />
      <section className="card">
        <div className="controls">
          {inviteRefused && <span role="alert">This invite link is not valid or has expired. </span>}
          <span className="hint">To start your own run, ask the author for an invite.</span>
        </div>
      </section>
      <section className="card">
        <Diagram numbers={null} pods={undefined} />
      </section>
      <BuildInfo />
    </main>
  )
}
