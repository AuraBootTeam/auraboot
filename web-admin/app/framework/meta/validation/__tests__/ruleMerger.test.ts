import { describe, expect, it } from 'vitest';
import { mergeRules } from '../ruleMerger';
import type { CrossFieldRule, RuleOverride } from '../crossFieldRuleTypes';

const rule = (id: string, message = id): CrossFieldRule => ({
  id, assert: { field: 'amount', gte: 0 }, message,
});

describe('command-level validation overrides', () => {
  it('returns an independent array when no overrides are supplied', () => {
    const model = [rule('positive')];
    const result = mergeRules(model, []);
    expect(result).toEqual(model);
    expect(result).not.toBe(model);
    result.pop();
    expect(model).toHaveLength(1);
  });

  it('accepts an absent optional override list from runtime DSL', () => {
    expect(mergeRules([rule('positive')], undefined as unknown as RuleOverride[]))
      .toEqual([rule('positive')]);
  });

  it('replaces a matching rule in place and preserves unrelated rules', () => {
    const replacement = { ...rule('positive', 'Must exceed ten'), assert: { field: 'amount', gt: 10 } };
    expect(mergeRules([rule('first'), rule('positive'), rule('last')], [replacement]))
      .toEqual([rule('first'), replacement, rule('last')]);
  });

  it('removes disabled model rules and never appends disabled new rules', () => {
    expect(mergeRules([rule('remove'), rule('keep')], [
      { ...rule('remove'), disabled: true }, { ...rule('new'), disabled: true },
    ])).toEqual([rule('keep')]);
  });

  it('appends new enabled rules after model rules in declared order', () => {
    expect(mergeRules([rule('base')], [rule('second'), rule('third')]))
      .toEqual([rule('base'), rule('second'), rule('third')]);
  });

  it('uses the last override for a duplicate id without duplicating the rule', () => {
    expect(mergeRules([rule('base')], [rule('base', 'first'), rule('base', 'last')]))
      .toEqual([rule('base', 'last')]);
    expect(mergeRules([], [rule('new', 'first'), rule('new', 'last')]))
      .toEqual([rule('new', 'last')]);
  });

  it('does not modify model rules or command overrides', () => {
    const model = Object.freeze([Object.freeze(rule('base'))]);
    const overrides = Object.freeze([Object.freeze({ ...rule('base'), disabled: true })]);
    expect(mergeRules(model as unknown as CrossFieldRule[], overrides as unknown as RuleOverride[]))
      .toEqual([]);
    expect(model).toEqual([rule('base')]);
    expect(overrides).toEqual([{ ...rule('base'), disabled: true }]);
  });
});
