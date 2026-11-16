export const SECTIONS = ['live', 'charts', 'slo', 'periods'] as const

export type Section = (typeof SECTIONS)[number]
