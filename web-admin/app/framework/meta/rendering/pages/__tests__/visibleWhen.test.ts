import { describe, expect, it } from 'vitest'
import { evaluateVisibleWhen } from '../utils/visibleWhen'

describe('evaluateVisibleWhen', () => {
  it('returns true when condition is absent', () => {
    expect(evaluateVisibleWhen(undefined)).toBe(true)
  })

  it('evaluates row-scoped expressions', () => {
    expect(
      evaluateVisibleWhen("row.status === 'draft'", {
        row: { status: 'draft' },
      }),
    ).toBe(true)
    expect(
      evaluateVisibleWhen("row.status === 'draft'", {
        row: { status: 'published' },
      }),
    ).toBe(false)
  })

  it('evaluates record and form aliases against the same record', () => {
    expect(
      evaluateVisibleWhen("record.status === 'active' && form.status === 'active'", {
        record: { status: 'active' },
      }),
    ).toBe(true)
  })

  it('fails closed when expression evaluation throws', () => {
    expect(
      evaluateVisibleWhen('row.status ===', {
        row: { status: 'draft' },
      }),
    ).toBe(false)
  })
})
