import { act, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { CrashIllustration } from './CrashIllustration'
import { BACK_AT_MS, CRASH_AT_MS, SCENE_CYCLE_MS, sceneAt } from './crashScene'
import { DOT_TRAVEL_MS } from './FlowDots'
import { en } from './texts/en'

const ledger = (rates: number[]) => rates.slice(2, 6)

describe('the crash scene', () => {
  it('flows, crashes at 2 s with the clients going on and the ledger wires still once Kafka has no results left, then catches up faster', () => {
    const flowing = sceneAt(0)
    const crashed = sceneAt(CRASH_AT_MS + 2 * DOT_TRAVEL_MS)
    const catchingUp = sceneAt(BACK_AT_MS + 2 * DOT_TRAVEL_MS)

    expect(sceneAt(CRASH_AT_MS - 1).phase).toBe('flowing')
    expect(crashed).toMatchObject({ phase: 'crashed', ledgerLook: 'dead' })
    expect(ledger(crashed.rates)).toEqual([0, 0, 0, 0])
    expect(crashed.rates[0]).toBe(flowing.rates[0])
    expect(catchingUp).toMatchObject({ phase: 'catching-up', ledgerLook: 'recovering' })
    ledger(catchingUp.rates).forEach((rate, wire) => expect(rate).toBeGreaterThan(ledger(flowing.rates)[wire]!))
    expect(sceneAt(9_000).phase).toBe('flowing')
    expect(sceneAt(SCENE_CYCLE_MS + CRASH_AT_MS).phase).toBe('crashed')
  })

  it('starts each hop on the way back one dot\'s travel after the hop before it, and stops the gateway\'s answers as late', () => {
    const on = (at: number) => sceneAt(at).rates.map((rate) => rate > 0)

    expect(on(BACK_AT_MS).slice(2, 6)).toEqual([true, false, false, false])
    expect(on(BACK_AT_MS + DOT_TRAVEL_MS).slice(2, 6)).toEqual([true, true, true, false])
    expect(on(BACK_AT_MS + 2 * DOT_TRAVEL_MS).slice(2, 6)).toEqual([true, true, true, true])
    expect(on(CRASH_AT_MS).slice(2, 6)).toEqual([false, false, false, true])
    expect(on(CRASH_AT_MS + DOT_TRAVEL_MS).slice(2, 6)).toEqual([false, false, false, false])
  })
})

describe('the illustration on the start page', () => {
  afterEach(() => {
    vi.useRealTimers()
    vi.unstubAllGlobals()
  })

  const phase = () => screen.getByRole('figure').getAttribute('data-phase')

  it('runs the scene and says it is an illustration', () => {
    vi.useFakeTimers()
    render(<CrashIllustration />)

    expect(phase()).toBe('flowing')
    act(() => vi.advanceTimersByTime(CRASH_AT_MS + 200))
    expect(phase()).toBe('crashed')
    expect(screen.getByText(en.crashIllustration.moving)).toBeInTheDocument()
  })

  it('stands still where the visitor asks for reduced motion', () => {
    vi.useFakeTimers()
    vi.stubGlobal('matchMedia', (query: string) => ({ matches: query.includes('reduce') }) as MediaQueryList)
    render(<CrashIllustration />)

    act(() => vi.advanceTimersByTime(CRASH_AT_MS + 200))

    expect(phase()).toBe('still')
    expect(screen.queryByText(en.crashIllustration.moving)).not.toBeInTheDocument()
  })

  it('pauses while the tab is hidden', () => {
    vi.useFakeTimers()
    const hidden = vi.spyOn(document, 'hidden', 'get').mockReturnValue(true)
    render(<CrashIllustration />)

    act(() => vi.advanceTimersByTime(CRASH_AT_MS + 200))
    expect(phase()).toBe('flowing')

    hidden.mockReturnValue(false)
    act(() => vi.advanceTimersByTime(CRASH_AT_MS + 200))
    expect(phase()).toBe('crashed')
  })
})
