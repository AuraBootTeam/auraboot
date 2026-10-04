import { describe, expect, it, vi, afterEach } from 'vitest';
import {
  evaluateScopedExpression,
  evaluateScopedCondition,
} from '../scopedEval';

describe('scopedEval security', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it.each([
    'globalThis.constructor.constructor("return process")()',
    'this.constructor.constructor("return 1")()',
    'constructor.constructor("return 1")()',
    'fetch("/admin")',
    'process.env.SECRET',
    'Function("return 1")()',
    'eval("1+1")',
  ])('rejects dangerous expression: %s', (expr) => {
    expect(() => evaluateScopedExpression(expr, {})).toThrow();
  });

  it('evaluates plain data conditions', () => {
    expect(
      evaluateScopedExpression("status === 'active' && amount > 10", {
        status: 'active',
        amount: 42,
      }),
    ).toBe(true);
  });

  it('resolves unknown identifiers leniently (undefined)', () => {
    expect(evaluateScopedExpression('missingField == null', {})).toBe(true);
  });

  it('evaluateScopedCondition denies (false) on broken expressions and logs', () => {
    const err = vi.spyOn(console, 'error').mockImplementation(() => {});
    expect(evaluateScopedCondition('status === ', {})).toBe(false);
    expect(err).toHaveBeenCalled();
  });

  it('evaluateScopedCondition on valid expression returns truthiness', () => {
    expect(evaluateScopedCondition('count > 3', { count: 5 })).toBe(true);
    expect(evaluateScopedCondition('count > 3', { count: 1 })).toBe(false);
  });
});
