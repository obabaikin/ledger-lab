import { act, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { CrashIllustration } from './CrashIllustration'
import { CRASH_AT_MS, SCENE_CYCLE_MS, sceneAt } from './crashScene'
import { en } from './texts/en'

const ledger = (rates: number[]) => rates.slice(2, 6)

describe('the crash scene', () => {
  it('flows, crashes at 2 s with the ledger wires still and the clients going on, then catches up faster', () => {
    const flowing = sceneAt(0)
    const crashed = sceneAt(CRASH_AT_MS)
    const catchingUp = sceneAt(6_000)

    expect(sceneAt(CRASH_AT_MS - 1).phase).toBe('flowing')
    expect(crashed).toMatchObject({ phase: 'crashed', ledgerLook: 'dead' })
    expect(ledger(crashed.rates)).toEqual([0, 0, 0, 0])
    expect(crashed.rates[0]).toBe(flowing.rates[0])
    expect(catchingUp).toMatchObject({ phase: 'catching-up', ledgerLook: 'recovering' })
    ledger(catchingUp.rates).forEach((rate, wire) => expect(rate).toBeGreaterThan(ledger(flowing.rates)[wire]!))
    expect(sceneAt(9_000).phase).toBe('flowing')
    expect(sceneAt(SCENE_CYCLE_MS + CRASH_AT_MS).phase).toBe('crashed')
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
