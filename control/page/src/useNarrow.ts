import { useEffect, useState } from 'react'

/** The width up to which the diagram stands upright: across, a phone would shrink it below reading size. */
const NARROW_QUERY = '(max-width: 640px)'

function narrowNow(): boolean {
  return typeof window.matchMedia === 'function' && window.matchMedia(NARROW_QUERY).matches
}

export function useNarrow(): boolean {
  const [narrow, setNarrow] = useState(narrowNow)
  useEffect(() => {
    if (typeof window.matchMedia !== 'function') return
    const query = window.matchMedia(NARROW_QUERY)
    if (typeof query.addEventListener !== 'function') return
    const follow = () => setNarrow(query.matches)
    query.addEventListener('change', follow)
    follow()
    return () => query.removeEventListener('change', follow)
  }, [])
  return narrow
}
