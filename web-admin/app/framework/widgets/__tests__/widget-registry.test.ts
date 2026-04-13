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

describe('propsSchema validation', () => {
  const moneySchema = {
    type: 'object',
    properties: {
      currency: { type: 'string', enum: ['CNY', 'USD', 'EUR'] },
      precision: { type: 'integer', minimum: 0, maximum: 8 },
    },
    required: ['currency'],
    additionalProperties: false,
  }

  it('compile failures surface at register time', () => {
    const r = new WidgetRegistry()
    expect(() =>
      r.register({
        type: 'broken',
        component: () => null,
        propsSchema: { type: 'not-a-real-type' as unknown as string },
      }),
    ).toThrow(/invalid propsSchema/)
  })

  it('valid props pass', () => {
    const r = new WidgetRegistry()
    r.register({ type: 'money-input', component: () => null, propsSchema: moneySchema })
    const result = r.validate('money-input', { currency: 'CNY', precision: 2 })
    expect(result).toEqual({ valid: true, errors: [] })
  })

  it('invalid props produce structured errors', () => {
    const r = new WidgetRegistry()
    r.register({ type: 'money-input', component: () => null, propsSchema: moneySchema })
    const result = r.validate('money-input', { precision: 99 }) // missing currency, precision OOR
    expect(result.valid).toBe(false)
    expect(result.errors.length).toBeGreaterThan(0)
    expect(result.errors.some(e => /currency/.test(e.path) || /currency/.test(e.message))).toBe(true)
  })

  it('unknown type returns explicit error', () => {
    const r = new WidgetRegistry()
    const result = r.validate('does-not-exist', {})
    expect(result.valid).toBe(false)
    expect(result.errors[0]?.message).toMatch(/unknown type/)
  })

  it('no propsSchema means no constraints', () => {
    const r = new WidgetRegistry()
    r.register({ type: 'unconstrained', component: () => null })
    expect(r.validate('unconstrained', { whatever: true }).valid).toBe(true)
  })

  it('assertValid throws with all error paths joined', () => {
    const r = new WidgetRegistry()
    r.register({ type: 'money-input', component: () => null, propsSchema: moneySchema })
    expect(() => r.assertValid('money-input', { currency: 'JPY' })).toThrow(/currency/)
  })

  it('assertValid passes silently on valid input', () => {
    const r = new WidgetRegistry()
    r.register({ type: 'money-input', component: () => null, propsSchema: moneySchema })
    expect(() => r.assertValid('money-input', { currency: 'USD' })).not.toThrow()
  })
})
