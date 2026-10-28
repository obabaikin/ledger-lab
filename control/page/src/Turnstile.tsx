import { useEffect, useRef } from 'react'

const SCRIPT_URL = 'https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit'

type TurnstileApi = {
  render: (container: HTMLElement, options: { sitekey: string; callback: (token: string) => void; 'expired-callback': () => void }) => string
  reset: (widgetId: string) => void
  remove: (widgetId: string) => void
}

declare global {
  interface Window {
    turnstile?: TurnstileApi
  }
}

let loading: Promise<TurnstileApi> | null = null

function loadTurnstile(): Promise<TurnstileApi> {
  if (window.turnstile) return Promise.resolve(window.turnstile)
  loading ??= new Promise((resolve, reject) => {
    const script = document.createElement('script')
    script.src = SCRIPT_URL
    script.async = true
    script.onload = () => (window.turnstile ? resolve(window.turnstile) : reject(new Error('Turnstile did not load')))
    script.onerror = () => {
      loading = null
      reject(new Error('Turnstile did not load'))
    }
    document.head.appendChild(script)
  })
  return loading
}

/** The widget's answer is good for one request: [resetKey] changing renders a fresh one. */
export function TurnstileWidget(props: {
  siteKey: string
  onToken: (token: string | null) => void
  onUnavailable: () => void
  resetKey: number
}) {
  const container = useRef<HTMLDivElement>(null)
  const { siteKey, onToken, onUnavailable, resetKey } = props

  useEffect(() => {
    let widgetId: string | null = null
    let current = true
    loadTurnstile()
      .then((turnstile) => {
        if (!current || container.current === null) return
        widgetId = turnstile.render(container.current, { sitekey: siteKey, callback: onToken, 'expired-callback': () => onToken(null) })
      })
      .catch(() => current && onUnavailable())
    return () => {
      current = false
      onToken(null)
      if (widgetId !== null) window.turnstile?.remove(widgetId)
    }
  }, [siteKey, onToken, onUnavailable, resetKey])

  return <div ref={container} className="turnstile" />
}
