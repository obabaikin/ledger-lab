export const NO_DATA = '—'
export const MS_PER_SECOND = 1000

export const count = (n: number) => Math.round(n).toLocaleString('en-US')

export const euros = (cents: number) =>
  '€' + (cents / 100).toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })

export const shown = (n: number | null | undefined, as: (n: number) => string = count) => (n == null ? NO_DATA : as(n))

const HOURS_AND_MINUTES: Intl.DateTimeFormatOptions = { hour: '2-digit', minute: '2-digit', hourCycle: 'h23' }

/** In the visitor's time zone. */
export const hoursMinutes = (iso: string) => new Date(iso).toLocaleTimeString('en-GB', HOURS_AND_MINUTES)

export const clock = (iso: string) => new Date(iso).toLocaleTimeString('en-GB', { ...HOURS_AND_MINUTES, second: '2-digit' })

export const dayAndMonth = (iso: string) => new Date(iso).toLocaleDateString('en-GB', { day: 'numeric', month: 'short' })

export const percent = (share: number, digits: number) => `${(share * 100).toFixed(digits)} %`
