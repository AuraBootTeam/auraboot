import { describe, expect, it } from 'vitest';
import { extractDependencies } from '../dependencyExtractor';
import type { CrossFieldRule } from '../crossFieldRuleTypes';

const rule = (parts: Partial<CrossFieldRule> = {}): CrossFieldRule => ({
  id: 'bounded-amount', message: 'Amount must fit the limit', assert: {}, ...parts,
});

describe('cross-field rule dependencies', () => {
  it('has no dependencies for a literal-only assertion', () => {
    expect(extractDependencies(rule({ assert: { eq: 1 } }))).toEqual(new Set());
  });

  it('tracks assertion and condition fields, deduplicating repeated fields', () => {
    expect(extractDependencies(rule({ assert: { field: 'amount' }, when: { field: 'amount', eq: 1 } })))
      .toEqual(new Set(['amount']));
  });

  it.each(['eq', 'neq', 'gt', 'gte', 'lt', 'lte'] as const)(
    'tracks field references in the %s assertion and condition', (operator) => {
      expect(extractDependencies(rule({
        assert: { field: 'amount', [operator]: { ref: 'limit' } },
        when: { field: 'status', [operator]: { ref: 'expectedStatus' } },
      }))).toEqual(new Set(['amount', 'limit', 'status', 'expectedStatus']));
    },
  );

  it('recursively tracks all nested and/or/not branches', () => {
    expect(extractDependencies(rule({ when: {
      and: [{ field: 'tenant' }, { or: [{ field: 'status' }, { not: { field: 'archived' } }] }],
    } }))).toEqual(new Set(['tenant', 'status', 'archived']));
  });

  it.each([0, false, '', null, { value: 'literal' }, []])(
    'does not interpret literal %j as a field reference', (eq) => {
      expect(extractDependencies(rule({ assert: { field: 'amount', eq } })))
        .toEqual(new Set(['amount']));
    },
  );

  it.each(['assert', 'when'] as const)('uses explicit dependencies for an %s expression', (location) => {
    const expressionRule = rule({
      assert: { field: 'ignored', ...(location === 'assert' ? { expr: 'amount > limit' } : {}) },
      when: location === 'when' ? { expr: 'enabled' } : undefined,
      dependsOn: ['amount', 'limit', 'amount'],
    });
    expect(extractDependencies(expressionRule)).toEqual(new Set(['amount', 'limit']));
  });

  it('does not infer expression fields when explicit dependencies are absent', () => {
    expect(extractDependencies(rule({ assert: { expr: 'amount > limit' } })))
      .toEqual(new Set());
  });
});
