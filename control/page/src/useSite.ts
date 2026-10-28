import { useEffect, useState } from 'react'
import { api, type SiteView } from './api/client'

/** null until control answers; the page shows no term it has not read (ADR 0014). */
export function useSite(): SiteView | null {
  const [site, setSite] = useState<SiteView | null>(null)
  useEffect(() => {
    let current = true
    api
      .site()
      .then((answer) => current && setSite(answer))
      .catch(() => {})
    return () => {
      current = false
    }
  }, [])
  return site
}
