import ts from 'typescript'
import { describe, expect, it } from 'vitest'
import css from './page.css?raw'

const components = import.meta.glob<string>(['./**/*.tsx', '!./**/*.test.tsx', '!./test/**'], { query: '?raw', import: 'default', eager: true })
const SCALE = ['--fs-hero', '--fs-number', '--fs-title', '--fs-body', '--fs-caption', '--fs-chart']
/** Attributes a person reads or hears. */
const READ_ATTRIBUTES = new Set(['aria-label', 'title', 'alt', 'placeholder'])

const rootRules = (sheet: string) => [...sheet.matchAll(/:root\s*\{[^}]*\}/g)].map((m) => m[0])
const outsideRoot = rootRules(css).reduce((sheet, rule) => sheet.replace(rule, ''), css)

/** Text in JSX and read attributes given as a literal: what a person would see in the code instead of in en.ts. */
function literals(file: string, source: string): string[] {
  const found: string[] = []
  const visit = (node: ts.Node) => {
    if (ts.isJsxText(node) && /\p{L}/u.test(node.text)) found.push(`${file}: ${node.text.trim()}`)
    if (ts.isJsxAttribute(node) && READ_ATTRIBUTES.has(node.name.getText()) && node.initializer && ts.isStringLiteral(node.initializer))
      found.push(`${file}: ${node.name.getText()}="${node.initializer.text}"`)
    ts.forEachChild(node, visit)
  }
  visit(ts.createSourceFile(file, source, ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX))
  return found
}

describe('type scale', () => {
  it('defines the six sizes once, and every size on the page is one of them', () => {
    expect(SCALE.every((size) => rootRules(css)[0]!.includes(`${size}:`))).toBe(true)
    expect(outsideRoot.match(/font(-size)?\s*:[^;]*\d+(px|em|rem)/g)).toBeNull()
    expect(Object.entries(components).filter(([, source]) => /fontSize\s*[=:]\s*\{?\s*\d/.test(source)).map(([file]) => file)).toEqual([])
  })

  it('uses two weights, regular and strong', () => {
    const weights = [...outsideRoot.matchAll(/font-weight\s*:\s*([^;}]+)/g)].map((m) => m[1]!.trim())
    expect(weights.filter((w) => !['var(--fw-regular)', 'var(--fw-strong)'].includes(w))).toEqual([])
    expect(css).toMatch(/--fw-regular:\s*400/)
    expect(css).toMatch(/--fw-strong:\s*600/)
  })

  it('keeps monospace for code and the formulas of the reconciliation only', () => {
    const monospaced = [...outsideRoot.matchAll(/([^{}]+)\{[^}]*monospace[^}]*\}/g)].map((m) => m[1]!.trim())
    expect(monospaced).toEqual(['.inv td.sym'])
  })

  it('keeps a line for the values under a chart, so they never come and go and move the page', () => {
    expect(css).toMatch(/\.readout\s*\{[^}]*min-height:\s*1\.5em/)
  })

  it('gives the live speed in the heading a fixed width of four digits', () => {
    expect(css).toMatch(/\.rate\s*\{[^}]*min-width:\s*calc\(4ch/)
  })
})

describe('text in components', () => {
  it('comes from en.ts, not from the code', () => {
    expect(Object.entries(components).flatMap(([file, source]) => literals(file, source))).toEqual([])
  })
})
