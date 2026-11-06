import { describe, expect, it } from 'vitest'
import { dotsPerSecond } from './FlowDots'

describe('dotsPerSecond', () => {
  it('a wire shows 24 dots per 1000 TPS, up to the rate cap', () => {
    expect(dotsPerSecond(1000, 0)).toBe(24)
    expect(dotsPerSecond(2000, 0)).toBe(48)
    expect(dotsPerSecond(5000, 0)).toBe(48)
  })

  it('the rejected wire shows more dots, so a rare rejection still shows', () => {
    expect(dotsPerSecond(1000, 4)).toBeGreaterThan(dotsPerSecond(1000, 0))
  })
})
