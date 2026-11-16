import { useEffect, useRef, useState } from 'react'
import { InviteForm } from './InviteForm'
import { t } from './texts'

export function InviteTeaser() {
  const [open, setOpen] = useState(false)
  const form = useRef<HTMLDivElement>(null)

  // Focus goes to the form, not its email field: the bot check loads only once the visitor clicks into the field (Privacy).
  useEffect(() => {
    if (open) form.current?.focus()
  }, [open])

  return open ? (
    <div ref={form} tabIndex={-1} className="teaser" data-testid="invite-opened">
      <InviteForm withHeading={false} />
    </div>
  ) : (
    <button type="button" className="primary" onClick={() => setOpen(true)}>
      {t.landing.tryIt}
    </button>
  )
}
