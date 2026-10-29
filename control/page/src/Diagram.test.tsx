import { render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { PodView } from './api/client'
import { Diagram, NO_NUMBERS } from './Diagram'

const pods: PodView[] = [
  { instance: 'gateway-0', job: 'gateway', up: true, partitions: null },
  { instance: 'ledger-0', job: 'ledger', up: true, partitions: 4 },
  { instance: 'ledger-1', job: 'ledger', up: true, partitions: 4 },
]

function drawn() {
  const svg = screen.getByRole('img')
  return { layout: svg.getAttribute('class'), viewBox: svg.getAttribute('viewBox'), wires: svg.querySelectorAll('path.wire').length }
}

describe('the diagram', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('lies across on a wide screen', () => {
    render(<Diagram numbers={NO_NUMBERS} pods={pods} />)

    expect(drawn()).toEqual({ layout: 'across', viewBox: '0 0 900 300', wires: 7 })
  })

  it('stands upright on a phone with every wire, number and pod still there', () => {
    vi.stubGlobal('matchMedia', (query: string) => ({ matches: query.includes('max-width') }) as MediaQueryList)
    render(<Diagram numbers={{ ...NO_NUMBERS, posted: 7 }} pods={pods} />)

    expect(drawn()).toEqual({ layout: 'upright', viewBox: '0 0 360 690', wires: 7 })
    expect(screen.getByTestId('ledger-number')).toHaveTextContent('7 booked')
    expect(screen.getByTestId('in-flight')).toBeInTheDocument()
    expect(screen.getAllByTestId('pod')).toHaveLength(3)
  })
})
