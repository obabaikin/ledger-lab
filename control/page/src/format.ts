export const NO_DATA = '—'
export const MS_PER_SECOND = 1000

export const count = (n: number) => Math.round(n).toLocaleString('en-US')

export const euros = (cents: number) =>
  '€' + (cents / 100).toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })

export const shown = (n: number | null | undefined, as: (n: number) => string = count) => (n == null ? NO_DATA : as(n))
