import { describe, it, expect, vi, beforeEach } from 'vitest';
import { ComputedFieldEngine } from '../ComputedFieldEngine';
import type { ComputedFieldDef, EvaluationContext } from '../types';

const def = (
  fieldCode: string,
  expression: string,
  dependencies: string[],
  extras?: Partial<ComputedFieldDef>,
): ComputedFieldDef => ({
  fieldCode,
  expression,
  dependencies,
  type: 'computed_readonly',
  ...extras,
});

const ctx = (form: Record<string, any> = {}): EvaluationContext => ({ form });

describe('ComputedFieldEngine', () => {
  let engine: ComputedFieldEngine;

  beforeEach(() => {
    engine = new ComputedFieldEngine();
  });

  // ─── register ──────────────────────────────────────────────────────────────

  describe('register', () => {
    it('returns success:true for acyclic fields', () => {
      const result = engine.register([
        def('total', 'price * qty', ['price', 'qty']),
      ]);
      expect(result.success).toBe(true);
      expect(result.cycle).toBeUndefined();
    });

    it('returns success:false and cycle for cyclic fields', () => {
      const result = engine.register([
        def('a', 'b', ['b']),
        def('b', 'a', ['a']),
      ]);
      expect(result.success).toBe(false);
      expect(result.cycle).toBeDefined();
      expect(result.cycle!.length).toBeGreaterThan(1);
    });

    it('removes cyclic fields from definitions after detecting cycle', () => {
      engine.register([
        def('x', 'y', ['y']),
        def('y', 'x', ['x']),
      ]);
      expect(engine.isComputed('x')).toBe(false);
      expect(engine.isComputed('y')).toBe(false);
    });

    it('registers valid fields as computed', () => {
      engine.register([def('total', 'price * qty', ['price', 'qty'])]);
      expect(engine.isComputed('total')).toBe(true);
    });
  });

  // ─── unregister ────────────────────────────────────────────────────────────

  describe('unregister', () => {
    it('removes the field from computed definitions', () => {
      engine.register([def('total', 'price * qty', ['price', 'qty'])]);
      engine.unregister('total');
      expect(engine.isComputed('total')).toBe(false);
    });

    it('removes cached result', () => {
      engine.register([def('f', '1', [])]);
      engine.evaluateAll(ctx());
      engine.unregister('f');
      expect(engine.getResult('f')).toBeUndefined();
    });
  });

  // ─── evaluateAll ───────────────────────────────────────────────────────────

  describe('evaluateAll', () => {
    it('evaluates a simple arithmetic expression', () => {
      engine.register([def('total', 'price * qty', ['price', 'qty'])]);
      const results = engine.evaluateAll(ctx({ price: 10, qty: 3 }));
      expect(results).toHaveLength(1);
      expect(results[0].fieldCode).toBe('total');
      expect(results[0].value).toBe(30);
      expect(results[0].stale).toBe(false);
    });

    it('evaluates chained fields in correct order', () => {
      engine.register([
        def('subtotal', 'price * qty', ['price', 'qty']),
        def('tax', 'subtotal * 0.1', ['subtotal']),
        def('total', 'subtotal + tax', ['subtotal', 'tax']),
      ]);
      const results = engine.evaluateAll(ctx({ price: 100, qty: 2 }));
      const totalResult = results.find((r) => r.fieldCode === 'total');
      expect(totalResult?.value).toBe(220); // 200 + 20
    });

    it('updates context with computed values for downstream fields', () => {
      engine.register([
        def('doubled', 'x * 2', ['x']),
        def('quadrupled', 'doubled * 2', ['doubled']),
      ]);
      const context = ctx({ x: 5 });
      engine.evaluateAll(context);
      expect(context.form['doubled']).toBe(10);
      expect(context.form['quadrupled']).toBe(20);
    });

    it('returns empty array for disposed engine', () => {
      engine.register([def('f', '1', [])]);
      engine.dispose();
      expect(engine.evaluateAll(ctx())).toHaveLength(0);
    });

    it('handles ${...} expression syntax', () => {
      engine.register([def('total', '${price + qty}', ['price', 'qty'])]);
      const results = engine.evaluateAll(ctx({ price: 5, qty: 3 }));
      expect(results[0].value).toBe(8);
    });
  });

  // ─── onFieldChange ─────────────────────────────────────────────────────────

  describe('onFieldChange', () => {
    it('returns affected results when a dependency changes', () => {
      engine.register([def('total', 'price * qty', ['price', 'qty'])]);
      const results = engine.onFieldChange('price', ctx({ price: 5, qty: 4 }));
      expect(results).toHaveLength(1);
      expect(results[0].fieldCode).toBe('total');
      expect(results[0].value).toBe(20);
    });

    it('returns empty array for field with no computed dependents', () => {
      engine.register([def('total', 'price * qty', ['price', 'qty'])]);
      const results = engine.onFieldChange('unrelated', ctx({ price: 1, qty: 1 }));
      expect(results).toHaveLength(0);
    });

    it('returns empty array for disposed engine', () => {
      engine.register([def('total', 'price * qty', ['price', 'qty'])]);
      engine.dispose();
      expect(engine.onFieldChange('price', ctx({ price: 1, qty: 1 }))).toHaveLength(0);
    });

    it('cascades through multiple levels', () => {
      engine.register([
        def('subtotal', 'price * qty', ['price', 'qty']),
        def('tax', 'subtotal * 0.1', ['subtotal']),
      ]);
      // First run evaluateAll so subtotal result is cached, then change price
      const context = ctx({ price: 10, qty: 2 });
      engine.evaluateAll(context); // builds subtotal=20 into context.form
      const results = engine.onFieldChange('price', context);
      expect(results.length).toBeGreaterThanOrEqual(1);
      // subtotal should be re-evaluated; tax uses the cached subtotal
      const subtotalResult = results.find((r) => r.fieldCode === 'subtotal');
      expect(subtotalResult?.value).toBe(20);
    });
  });

  // ─── error handling ────────────────────────────────────────────────────────

  describe('error handling', () => {
    it('sets stale:true and includes error message on exception', () => {
      engine.register([def('bad', 'undefined_var.nonexistent', ['x'])]);
      const results = engine.evaluateAll(ctx({ x: 1 }));
      const badResult = results.find((r) => r.fieldCode === 'bad');
      expect(badResult?.stale).toBe(true);
      expect(badResult?.error).toBeDefined();
    });

    it('uses fallbackValue when expression errors', () => {
      engine.register([
        def('safe', 'undefined_var.boom', ['x'], { fallbackValue: -1 }),
      ]);
      const results = engine.evaluateAll(ctx({ x: 1 }));
      expect(results[0].value).toBe(-1);
    });

    it('calls onError callback on expression failure', () => {
      const onError = vi.fn();
      const eng = new ComputedFieldEngine({ onError });
      eng.register([def('f', 'null.boom', ['x'])]);
      eng.evaluateAll(ctx({ x: 1 }));
      expect(onError).toHaveBeenCalledWith('f', expect.any(Error));
    });
  });

  // ─── onChange callback ─────────────────────────────────────────────────────

  describe('onChange callback', () => {
    it('fires onChange when value changes', () => {
      const onChange = vi.fn();
      const eng = new ComputedFieldEngine({ onChange });
      eng.register([def('doubled', 'x * 2', ['x'])]);
      eng.evaluateAll(ctx({ x: 5 }));
      expect(onChange).toHaveBeenCalledWith('doubled', 10, undefined);
    });

    it('does not fire onChange when value is the same', () => {
      const onChange = vi.fn();
      const eng = new ComputedFieldEngine({ onChange });
      eng.register([def('fixed', '42', [])]);
      eng.evaluateAll(ctx());
      onChange.mockClear();
      eng.evaluateAll(ctx());
      expect(onChange).not.toHaveBeenCalled();
    });

    it('fires onChange with previous value on second evaluation', () => {
      const onChange = vi.fn();
      const eng = new ComputedFieldEngine({ onChange });
      eng.register([def('double', 'x * 2', ['x'])]);

      const context1 = ctx({ x: 5 });
      eng.evaluateAll(context1);
      onChange.mockClear();

      const context2 = ctx({ x: 10 });
      eng.onFieldChange('x', context2);
      expect(onChange).toHaveBeenCalledWith('double', 20, 10);
    });
  });

  // ─── getResult / getAllResults ─────────────────────────────────────────────

  describe('getResult / getAllResults', () => {
    it('getResult returns undefined before evaluation', () => {
      engine.register([def('f', '1', [])]);
      expect(engine.getResult('f')).toBeUndefined();
    });

    it('getResult returns the result after evaluation', () => {
      engine.register([def('f', '99', [])]);
      engine.evaluateAll(ctx());
      const r = engine.getResult('f');
      expect(r?.value).toBe(99);
    });

    it('getAllResults returns a Map with all evaluated fields', () => {
      engine.register([
        def('a', '1', []),
        def('b', '2', []),
      ]);
      engine.evaluateAll(ctx());
      const all = engine.getAllResults();
      expect(all.size).toBe(2);
      expect(all.get('a')?.value).toBe(1);
      expect(all.get('b')?.value).toBe(2);
    });

    it('getAllResults returns a snapshot (copy) of the results', () => {
      engine.register([def('f', '5', [])]);
      engine.evaluateAll(ctx());
      const snapshot = engine.getAllResults();
      engine.unregister('f');
      expect(snapshot.size).toBe(1);
    });
  });

  // ─── getDefinitions / getGraph ─────────────────────────────────────────────

  describe('getDefinitions / getGraph', () => {
    it('getDefinitions returns registered definitions', () => {
      engine.register([def('total', 'price * qty', ['price', 'qty'])]);
      const defs = engine.getDefinitions();
      expect(defs).toHaveLength(1);
      expect(defs[0].fieldCode).toBe('total');
    });

    it('getGraph returns the DependencyGraph', () => {
      engine.register([def('f', '1', [])]);
      const g = engine.getGraph();
      expect(g).toBeDefined();
      expect(g.isComputed('f')).toBe(true);
    });
  });

  // ─── debounce ──────────────────────────────────────────────────────────────

  describe('debounce', () => {
    it('does not return immediate results for debounced fields', () => {
      vi.useFakeTimers();
      engine.register([def('f', 'x * 2', ['x'], { debounceMs: 200 })]);
      const results = engine.onFieldChange('x', ctx({ x: 5 }));
      expect(results).toHaveLength(0); // debounced, no immediate result
      vi.useRealTimers();
    });
  });

  // ─── dispose ───────────────────────────────────────────────────────────────

  describe('dispose', () => {
    it('marks engine as disposed (evaluateAll returns empty)', () => {
      engine.register([def('f', '1', [])]);
      engine.dispose();
      expect(engine.evaluateAll(ctx())).toHaveLength(0);
    });

    it('clears all definitions', () => {
      engine.register([def('f', '1', [])]);
      engine.dispose();
      expect(engine.getDefinitions()).toHaveLength(0);
    });
  });
});
