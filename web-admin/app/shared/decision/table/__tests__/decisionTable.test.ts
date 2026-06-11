import { describe, it, expect } from 'vitest';
import { evaluateTablePreview, validateTable, type DecisionTable, type HitPolicy } from '../decisionTable';
import type { ScopedContext } from '../../ast/conditionAst';

const routing = (hitPolicy: HitPolicy, withDefault: boolean): DecisionTable => ({
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

  it('COLLECT SUM aggregates all matched numeric outputs', () => {
    const t: DecisionTable = {
      hitPolicy: 'COLLECT',
      aggregation: 'SUM',
      inputs: [{ id: 'amount', label: 'A', scope: 'record', path: 'data.amount', dataType: 'decimal' }],
      outputs: [{ id: 'score', label: 'Score', dataType: 'decimal' }],
      rules: [
        { ruleId: 'base', priority: 10, when: { amount: { operator: 'GT', value: 1000 } }, then: { score: 10 } },
        { ruleId: 'large', priority: 20, when: { amount: { operator: 'GT', value: 5000 } }, then: { score: 15 } },
      ],
    };
    expect(evaluateTablePreview(t, ctx(20000))).toMatchObject({
      status: 'MATCHED',
      matchedRuleId: 'base,large',
      outputs: { score: 25 },
    });
  });

  it('PRIORITY returns the highest allowed output value', () => {
    const t: DecisionTable = {
      hitPolicy: 'PRIORITY',
      inputs: [{ id: 'amount', label: 'A', scope: 'record', path: 'data.amount', dataType: 'decimal' }],
      outputs: [{ id: 'risk', label: 'Risk', dataType: 'enum', allowedValues: ['HIGH', 'MEDIUM', 'LOW'] }],
      rules: [
        { ruleId: 'medium', priority: 10, when: { amount: { operator: 'GT', value: 1000 } }, then: { risk: 'MEDIUM' } },
        { ruleId: 'high', priority: 20, when: { amount: { operator: 'GT', value: 5000 } }, then: { risk: 'HIGH' } },
      ],
    };
    expect(evaluateTablePreview(t, ctx(20000))).toMatchObject({
      status: 'MATCHED',
      matchedRuleId: 'high',
      outputs: { risk: 'HIGH' },
    });
  });

  it('FEEL cell text supports ranges and comma lists', () => {
    const t: DecisionTable = {
      hitPolicy: 'UNIQUE',
      inputs: [
        { id: 'amount', label: 'Amount', scope: 'record', path: 'data.amount', dataType: 'decimal' },
        { id: 'priority', label: 'Priority', scope: 'record', path: 'data.priority', dataType: 'enum' },
      ],
      outputs: [{ id: 'route', label: 'Route', dataType: 'string' }],
      rules: [{
        ruleId: 'feel-row',
        when: {
          amount: { operator: 'EQ', value: '', feel: '[10000..50000]' },
          priority: { operator: 'EQ', value: '', feel: 'HIGH, CRITICAL' },
        },
        then: { route: 'director' },
      }],
    };
    expect(evaluateTablePreview(t, ctx(20000, 'HIGH'))).toMatchObject({
      status: 'MATCHED',
      matchedRuleId: 'feel-row',
      outputs: { route: 'director' },
    });
  });

  it('FEEL cell text supports date comparisons', () => {
    const t: DecisionTable = {
      hitPolicy: 'FIRST',
      inputs: [
        { id: 'submittedOn', label: 'Submitted On', scope: 'record', path: 'data.submittedOn', dataType: 'date' },
      ],
      outputs: [{ id: 'route', label: 'Route', dataType: 'string' }],
      rules: [{
        ruleId: 'recent',
        when: { submittedOn: { operator: 'EQ', value: '', feel: '>= 2026-06-01' } },
        then: { route: 'recent' },
      }],
    };
    expect(evaluateTablePreview(t, { record: { data: { submittedOn: '2026-06-15' } } })).toMatchObject({
      status: 'MATCHED',
      matchedRuleId: 'recent',
      outputs: { route: 'recent' },
    });
  });

  it('FEEL cell text supports whitelisted date and duration functions', () => {
    const t: DecisionTable = {
      hitPolicy: 'FIRST',
      inputs: [
        { id: 'submittedOn', label: 'Submitted On', scope: 'record', path: 'data.submittedOn', dataType: 'date' },
        { id: 'sla', label: 'SLA', scope: 'record', path: 'data.sla', dataType: 'duration' },
      ],
      outputs: [{ id: 'route', label: 'Route', dataType: 'string' }],
      rules: [
        {
          ruleId: 'fast',
          when: {
            submittedOn: { operator: 'EQ', value: '', feel: '>= date(2026, 6, 10)' },
            sla: { operator: 'EQ', value: '', feel: '<= duration("P2D")' },
          },
          then: { route: 'fast' },
        },
        { ruleId: 'fallback', when: {}, then: { route: 'fallback' } },
      ],
    };
    expect(evaluateTablePreview(t, {
      record: { data: { submittedOn: '2026-06-11', sla: 'P1D' } },
    })).toMatchObject({
      status: 'MATCHED',
      matchedRuleId: 'fast',
      outputs: { route: 'fast' },
    });
    expect(validateTable(t)).toEqual([]);
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
