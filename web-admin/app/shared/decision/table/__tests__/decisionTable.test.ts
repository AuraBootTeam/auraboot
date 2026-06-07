import { describe, it, expect } from 'vitest';
import { evaluateTablePreview, validateTable, type DecisionTable } from '../decisionTable';
import type { ScopedContext } from '../../ast/conditionAst';

const routing = (hitPolicy: 'FIRST' | 'UNIQUE', withDefault: boolean): DecisionTable => ({
  hitPolicy,
  inputs: [
    { id: 'amount', label: 'Amount', scope: 'record', path: 'data.amount', dataType: 'decimal' },
    { id: 'priority', label: 'Priority', scope: 'record', path: 'data.priority', dataType: 'enum' },
  ],
  outputs: [{ id: 'route', label: 'Route', dataType: 'string' }],
  rules: [
    { ruleId: 'row-1', priority: 10, when: { amount: { operator: 'LTE', value: 10000 }, priority: { operator: 'EQ', value: 'NORMAL' } }, then: { route: 'manager' } },
    { ruleId: 'row-2', priority: 20, when: { amount: { operator: 'GT', value: 10000 }, priority: { operator: 'EQ', value: 'HIGH' } }, then: { route: 'director' } },
  ],
  defaultOutput: withDefault ? { route: 'fallback' } : undefined,
});

const ctx = (amount: unknown, priority?: unknown): ScopedContext =>
  ({ record: { data: priority === undefined ? { amount } : { amount, priority } } });

describe('evaluateTablePreview (mirrors backend §15)', () => {
  it('FIRST hit returns the matching row outputs', () => {
    const t = routing('FIRST', false);
    expect(evaluateTablePreview(t, ctx(20000, 'HIGH'))).toMatchObject({ status: 'MATCHED', matchedRuleId: 'row-2', outputs: { route: 'director' } });
    expect(evaluateTablePreview(t, ctx(500, 'NORMAL'))).toMatchObject({ matchedRuleId: 'row-1', outputs: { route: 'manager' } });
  });

  it('default output when no row matches', () => {
    expect(evaluateTablePreview(routing('FIRST', true), ctx(99999, 'LOW')))
      .toMatchObject({ status: 'MATCHED', matchedRuleId: '__default__', outputs: { route: 'fallback' } });
  });

  it('NOT_MATCHED when no row and no default', () => {
    expect(evaluateTablePreview(routing('FIRST', false), ctx(99999, 'LOW')).status).toBe('NOT_MATCHED');
  });

  it('missing input -> UNKNOWN, not false', () => {
    expect(evaluateTablePreview(routing('FIRST', false), ctx(20000)).status).toBe('UNKNOWN');
  });

  it('UNIQUE with multiple matches -> ERROR', () => {
    const t: DecisionTable = {
      hitPolicy: 'UNIQUE',
      inputs: [{ id: 'amount', label: 'A', scope: 'record', path: 'data.amount', dataType: 'decimal' }],
      outputs: [{ id: 'route', label: 'R', dataType: 'string' }],
      rules: [
        { ruleId: 'a', priority: 10, when: { amount: { operator: 'GT', value: 1000 } }, then: { route: 'x' } },
        { ruleId: 'b', priority: 20, when: { amount: { operator: 'GT', value: 5000 } }, then: { route: 'y' } },
      ],
    };
    const r = evaluateTablePreview(t, ctx(20000));
    expect(r.status).toBe('ERROR');
    expect(r.errors[0]).toContain('UNIQUE');
  });
});

describe('validateTable', () => {
  it('flags unknown input/output refs', () => {
    const t: DecisionTable = {
      hitPolicy: 'FIRST',
      inputs: [{ id: 'amount', label: 'A', scope: 'record', path: 'data.amount', dataType: 'decimal' }],
      outputs: [{ id: 'route', label: 'R', dataType: 'string' }],
      rules: [{ ruleId: 'r', when: { nope: { operator: 'GT', value: 1 } }, then: { ghost: 'x' } }],
    };
    const errs = validateTable(t);
    expect(errs.some((e) => e.includes("unknown input 'nope'"))).toBe(true);
    expect(errs.some((e) => e.includes("unknown output 'ghost'"))).toBe(true);
  });

  it('passes a well-formed table', () => {
    expect(validateTable(routing('FIRST', false))).toEqual([]);
  });
});
