import { describe, it, expect } from 'vitest'
import { evaluateAccess } from '../evaluator'

const user = (permissions: string[] = [], features: string[] = []) => ({
  permissions: new Set(permissions),
  features: new Set(features),
})

describe('evaluateAccess', () => {
  it('grants visibility when no requirements specified', () => {
    expect(evaluateAccess({}, user()).visible).toBe(true)
  })

  it('hidden flag short-circuits', () => {
    const d = evaluateAccess({ hidden: true, permission: 'x' }, user(['x']))
    expect(d).toEqual({ visible: false, reason: 'hidden' })
  })

  it('reports first missing permission', () => {
    const d = evaluateAccess(
      { permission: ['a', 'b', 'c'] },
      user(['a']),
    )
    expect(d).toEqual({ visible: false, reason: 'missing_permission', missing: 'b' })
  })

  it('reports missing feature when permissions ok', () => {
    const d = evaluateAccess(
      { permission: 'p1', featureKey: 'f1' },
      user(['p1']),
    )
    expect(d).toEqual({ visible: false, reason: 'missing_feature', missing: 'f1' })
  })

  it('grants when all permissions and features present', () => {
    const d = evaluateAccess(
      { permission: ['p1', 'p2'], featureKey: ['f1'] },
      user(['p1', 'p2', 'extra'], ['f1', 'f2']),
    )
    expect(d).toEqual({ visible: true })
  })

  it('accepts string and array forms interchangeably', () => {
    expect(evaluateAccess({ permission: 'p1' }, user(['p1'])).visible).toBe(true)
    expect(evaluateAccess({ permission: ['p1'] }, user(['p1'])).visible).toBe(true)
  })

  it('accepts array user.permissions', () => {
    expect(evaluateAccess({ permission: 'p1' }, { permissions: ['p1'], features: [] }).visible).toBe(true)
  })
})
