import { describe, it, expect } from 'vitest'
import { WidgetRegistry, ColumnRendererRegistry } from '../widget-registry'

describe('WidgetRegistry', () => {
  it('registers and resolves widgets', () => {
    const r = new WidgetRegistry()
    r.register({ type: 'money-input', component: () => null })
    expect(r.resolve('money-input')?.type).toBe('money-input')
    expect(r.resolve('unknown')).toBeUndefined()
  })

  it('throws on duplicate registration without override', () => {
    const r = new WidgetRegistry()
    r.register({ type: 'date-picker', component: () => null, plugin: 'core.system' })
    expect(() =>
      r.register({ type: 'date-picker', component: () => null, plugin: 'ent.x' }),
    ).toThrow(/already registered/)
  })

  it('allows override when override=true', () => {
    const r = new WidgetRegistry()
    const a = () => null
    const b = () => null
    r.register({ type: 'date-picker', component: a })
    r.register({ type: 'date-picker', component: b, override: true })
    expect(r.resolve('date-picker')?.component).toBe(b)
  })

  it('list returns all registrations', () => {
    const r = new WidgetRegistry()
    r.register({ type: 'a', component: () => null })
    r.register({ type: 'b', component: () => null })
    expect(r.list().map(x => x.type).sort()).toEqual(['a', 'b'])
  })
})

describe('ColumnRendererRegistry', () => {
  it('isolates from WidgetRegistry', () => {
    const wr = new WidgetRegistry()
    const cr = new ColumnRendererRegistry()
    wr.register({ type: 'foo', component: () => null })
    cr.register({ type: 'foo', component: () => null }) // same type, different registry
    expect(wr.resolve('foo')?.component).not.toBe(cr.resolve('foo')?.component)
  })
})
