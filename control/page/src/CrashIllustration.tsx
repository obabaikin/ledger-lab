import { useEffect, useState } from 'react'
import { sceneAt, type Scene } from './crashScene'
import { Diagram } from './Diagram'
import { t } from './texts'

const TICK_MS = 100

function reducedMotion(): boolean {
  return typeof window.matchMedia === 'function' && window.matchMedia('(prefers-reduced-motion: reduce)').matches
}

/** Time runs only while the tab is visible, so the scene goes on where it was left. */
export function CrashIllustration() {
  const [still] = useState(reducedMotion)
  const [scene, setScene] = useState<Scene>(() => sceneAt(0))

  useEffect(() => {
    if (still) return
    let elapsed = 0
    let last = performance.now()
    const timer = setInterval(() => {
      const now = performance.now()
      if (!document.hidden) elapsed += now - last
      last = now
      const next = sceneAt(elapsed)
      setScene((shown) => (shown.phase === next.phase ? shown : next))
    }, TICK_MS)
    return () => clearInterval(timer)
  }, [still])

  return (
    <figure className="crash-scene" data-phase={still ? 'still' : scene.phase}>
      <Diagram numbers={null} pods={undefined} rates={still ? undefined : scene.rates} ledgerLook={still ? null : scene.ledgerLook} />
      <figcaption className="hint illustration">
        {still ? t.crashIllustration.still : t.crashIllustration.moving}
      </figcaption>
    </figure>
  )
}
