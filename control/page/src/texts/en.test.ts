import { describe, expect, it } from 'vitest'
import { en } from './en'

/** Pieces set into the middle of a line, after a number or another phrase: they stay lower case. */
const FRAGMENTS = new Set([
  'hero.unit',
  'hero.after',
  'bank.transfers',
  'bank.accounts',
  'bank.unchanged',
  'bank.was',
  'availability.windowSince',
  'report.loadPulse',
  'report.testsLink',
  'report.testsAfter',
  'privacy.generatorAfter',
  'footer.by',
])

/** Not text for people: a link's address. */
const NOT_TEXT = new Set(['privacy.generatorUrl'])

/** Full sentences, which end with a full stop; any other text is a label and has none. A key ending in '.' covers its group. */
const SENTENCES = [
  'hero.after',
  'hero.baseChangesIn',
  'hero.base',
  'hero.pulse',
  'hero.reconciled',
  'inviteForm.sent',
  'inviteForm.deletionLink',
  'inviteForm.linksPerDay',
  'inviteForm.terms',
  'inviteForm.notAnAddress',
  'inviteForm.verificationFailed',
  'inviteForm.couldNotSend',
  'inviteForm.checkUnavailable',
  'inviteForm.tooManyRequests',
  'deletePage.explanation',
  'deletePage.asked',
  'deletePage.askedBefore',
  'deletePage.unknown',
  'deletePage.failed',
  'privacy.',
  'landing.inviteRefused',
  'sessionEnded.message',
  'connection.',
  'bank.running',
  'bank.notStarted',
  'bank.clientsRestarted',
  'bank.lastCrash',
  'controls.raised',
  'controls.crashed',
  'controls.inviteNotValid',
  'controls.controlDidNotAnswer',
  'controls.load.',
  'controls.chaos.',
  'charts.noData',
  'charts.unavailable',
  'availability.sliIntro',
  'availability.rule',
  'periods.current',
  'periods.firstCloses',
  'periods.failure.',
  'periods.unavailable',
  'periods.notRead',
  'report.mismatch.',
  'report.note.',
  'report.testsAfter',
]

const LOWER_CASE_TERM = /^p\d+\b/
const LEADING_MARKS = /^[\s⚡✓✗●←→↑↓≈·(“"']+/u
const SAMPLE = 7

type Shown = { key: string; text: string }

function shown(value: unknown, key: string, out: Shown[]) {
  if (typeof value === 'string') out.push({ key, text: value })
  else if (typeof value === 'function') out.push({ key, text: String((value as (...a: unknown[]) => unknown)(SAMPLE, SAMPLE, SAMPLE)) })
  else if (value !== null && typeof value === 'object') for (const [k, v] of Object.entries(value)) shown(v, key === '' ? k : `${key}.${k}`, out)
  return out
}

const isSentence = (key: string) => SENTENCES.some((s) => (s.endsWith('.') ? key.startsWith(s) : key === s))
const texts = shown(en, '', []).filter(({ key }) => !NOT_TEXT.has(key))

describe('page texts', () => {
  it('start with a capital letter, except the fragments set into a line and terms written in lower case', () => {
    const lower = texts.filter(({ key, text }) => {
      if (FRAGMENTS.has(key)) return false
      const start = text.replace(LEADING_MARKS, '')
      if (!/^\p{L}/u.test(start) || LOWER_CASE_TERM.test(start)) return false
      return start[0] !== start[0]!.toUpperCase()
    })

    expect(lower).toEqual([])
  })

  it('end with a full stop only where they are a sentence', () => {
    const labelsWithStop = texts.filter(({ key, text }) => /\.\s*$/.test(text) && !isSentence(key))

    expect(labelsWithStop).toEqual([])
  })

  it('name only keys that exist as fragments', () => {
    const keys = new Set(texts.map(({ key }) => key))

    expect([...FRAGMENTS].filter((key) => !keys.has(key))).toEqual([])
  })

  it('write a speed as a number per second and a share with a space before the percent sign', () => {
    expect(texts.filter(({ text }) => /\d%|transfers per second\b/i.test(text) && !/^Transfers per second$/.test(text))).toEqual([])
  })
})
