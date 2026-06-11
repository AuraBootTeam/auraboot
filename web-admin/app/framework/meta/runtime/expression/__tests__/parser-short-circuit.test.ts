/**
 * Regression: `&&` / `||` / `??` must short-circuit.
 *
 * jsep parses these as BinaryExpressions, and the evaluator previously
 * evaluated BOTH operands eagerly — so a guard like
 * `x != null && x.includes(y)` invoked `.includes` on a null/undefined `x`
 * and threw "尝试调用非函数值". This broke every guarded DSL expression
 * (visibleWhen / activeWhen / filter guards). The right operand must only be
 * evaluated when the left makes it necessary.
 */

import { describe, it, expect } from 'vitest';
import { ExpressionParser } from '../parser';
import { createExpressionContext } from '../context';

describe('ExpressionParser logical short-circuit', () => {
  it('&& does not evaluate the right operand when the left is false', () => {
    // statusFilter is undefined — `!= null` is false, so `.includes` must be skipped.
    const parser = new ExpressionParser(createExpressionContext({ state: {} } as any));
    expect(() =>
      parser.evaluate("${state.statusFilter != null && state.statusFilter.includes('ONLINE')}"),
    ).not.toThrow();
    expect(
      parser.evaluate("${state.statusFilter != null && state.statusFilter.includes('ONLINE')}"),
    ).toBe(false);
  });

  it('|| does not evaluate the right operand when the left is true', () => {
    const parser = new ExpressionParser(createExpressionContext({ state: {} } as any));
    expect(() =>
      parser.evaluate("${state.statusFilter == null || state.statusFilter.includes('ONLINE')}"),
    ).not.toThrow();
    expect(
      parser.evaluate("${state.statusFilter == null || state.statusFilter.includes('ONLINE')}"),
    ).toBe(true);
  });

  it('still evaluates the right operand when the guard passes', () => {
    const parser = new ExpressionParser(
      createExpressionContext({ state: { statusFilter: ['ONLINE'] } } as any),
    );
    expect(
      parser.evaluate(
        "${state.statusFilter != null && state.statusFilter.includes('ONLINE') && state.statusFilter.length == 1}",
      ),
    ).toBe(true);
    expect(
      parser.evaluate("${state.statusFilter != null && state.statusFilter.includes('OFFLINE')}"),
    ).toBe(false);
  });

  it('?? coalesces without evaluating the right operand when the left is non-null', () => {
    const parser = new ExpressionParser(
      createExpressionContext({ state: { keyword: 'abc' } } as any),
    );
    expect(parser.evaluate('${state.keyword ?? state.missing.deep}')).toBe('abc');
  });

  it('resolves an unknown identifier to undefined instead of throwing', () => {
    // Row-action guards (e.g. `record.x == 'Y'`) are evaluated in contexts that
    // legitimately lack `record`; the condition must safely resolve, not throw.
    const parser = new ExpressionParser(createExpressionContext({} as any));
    expect(() => parser.evaluate("${record.iot_oj_state == 'ROLLING'}")).not.toThrow();
    expect(parser.evaluate("${record.iot_oj_state == 'ROLLING'}")).toBe(false);
    expect(
      parser.evaluate("${record.iot_oj_state == 'DRAFT' || record.iot_oj_state == 'PAUSED'}"),
    ).toBe(false);
  });
});
