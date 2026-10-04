import { describe, expect, it } from 'vitest';
import { evaluateAssert } from '../assertEvaluator';

const pass = { passed: true, skipped: false };
const skip = { passed: true, skipped: true };
const fail = (failedOperator: string) => ({ passed: false, skipped: false, failedOperator });

describe('cross-field assertion evaluation', () => {
  it('passes an assertion without a field', () => {
    expect(evaluateAssert({}, {})).toEqual(pass);
  });

  it.each([undefined, null, '', '  '])('rejects required empty value %j', (value) => {
    expect(evaluateAssert({ field: 'amount', required: true }, { amount: value })).toEqual(fail('required'));
  });

  it.each([0, false, 'valid'])('accepts required non-empty value %j', (value) => {
    expect(evaluateAssert({ field: 'amount', required: true }, { amount: value })).toEqual(pass);
  });

  it.each([undefined, null])('explicitly skips optional absent value %j', (value) => {
    expect(evaluateAssert({ field: 'amount', gt: 0 }, { amount: value })).toEqual(skip);
  });

  it.each([
    ['eq', 2, 2, true], ['eq', 2, 3, false],
    ['neq', 2, 3, true], ['neq', 2, 2, false],
    ['gt', 3, 2, true], ['gt', 2, 2, false],
    ['gte', 2, 2, true], ['gte', 1, 2, false],
    ['lt', 1, 2, true], ['lt', 2, 2, false],
    ['lte', 2, 2, true], ['lte', 3, 2, false],
    ['eq', 'b', 'b', true], ['eq', 'b', 'a', false],
    ['neq', 'b', 'a', true], ['neq', 'b', 'b', false],
    ['gt', 'b', 'a', true], ['gt', 'a', 'a', false],
    ['gte', 'a', 'a', true], ['gte', 'a', 'b', false],
    ['lt', 'a', 'b', true], ['lt', 'b', 'b', false],
    ['lte', 'b', 'b', true], ['lte', 'b', 'a', false],
    ['eq', true, true, true], ['eq', true, 1, false],
    ['neq', false, true, true], ['neq', false, false, false],
    ['gt', true, false, false],
  ] as const)('%s compares %j with %j (pass=%j)', (operator, value, rhs, passed) => {
    expect(evaluateAssert({ field: 'amount', [operator]: rhs }, { amount: value }))
      .toEqual(passed ? pass : fail(operator));
  });

  it('resolves comparison values from other fields and skips an absent reference', () => {
    expect(evaluateAssert({ field: 'amount', lte: { ref: 'limit' } }, { amount: 5, limit: 6 })).toEqual(pass);
    expect(evaluateAssert({ field: 'amount', lte: { ref: 'limit' } }, { amount: 7, limit: 6 })).toEqual(fail('lte'));
    expect(evaluateAssert({ field: 'amount', lte: { ref: 'limit' } }, { amount: 7 })).toEqual(skip);
  });

  it('applies all comparison constraints instead of stopping after a successful one', () => {
    expect(evaluateAssert({ field: 'amount', gte: 1, lte: 5 }, { amount: 3 })).toEqual(pass);
    expect(evaluateAssert({ field: 'amount', gte: 1, lte: 5 }, { amount: 6 })).toEqual(fail('lte'));
  });

  it('enforces inclusive string length boundaries', () => {
    expect(evaluateAssert({ field: 'code', minLength: 2, maxLength: 3 }, { code: 'ab' })).toEqual(pass);
    expect(evaluateAssert({ field: 'code', minLength: 2, maxLength: 3 }, { code: 'abc' })).toEqual(pass);
    expect(evaluateAssert({ field: 'code', minLength: 2 }, { code: 'a' })).toEqual(fail('minLength'));
    expect(evaluateAssert({ field: 'code', maxLength: 3 }, { code: 'abcd' })).toEqual(fail('maxLength'));
    expect(evaluateAssert({ field: 'code', minLength: 2, maxLength: 3 }, { code: 1234 })).toEqual(pass);
  });

  it('enforces patterns on strings and fails fast on an invalid pattern', () => {
    expect(evaluateAssert({ field: 'code', pattern: '^A[0-9]+$' }, { code: 'A12' })).toEqual(pass);
    expect(evaluateAssert({ field: 'code', pattern: '^A[0-9]+$' }, { code: 'B12' })).toEqual(fail('pattern'));
    expect(evaluateAssert({ field: 'code', pattern: '^A' }, { code: 12 })).toEqual(pass);
    expect(() => evaluateAssert({ field: 'code', pattern: '[' }, { code: 'A' })).toThrow(SyntaxError);
  });

  it('enforces allow and deny lists including empty sets', () => {
    expect(evaluateAssert({ field: 'status', in: ['ready'], notIn: ['closed'] }, { status: 'ready' })).toEqual(pass);
    expect(evaluateAssert({ field: 'status', in: ['ready'] }, { status: 'closed' })).toEqual(fail('in'));
    expect(evaluateAssert({ field: 'status', notIn: ['closed'] }, { status: 'closed' })).toEqual(fail('notIn'));
    expect(evaluateAssert({ field: 'status', in: [] }, { status: 'ready' })).toEqual(fail('in'));
    expect(evaluateAssert({ field: 'status', notIn: [] }, { status: 'ready' })).toEqual(pass);
  });

  it('evaluates valid scoped expressions and denies false or invalid expressions', () => {
    expect(evaluateAssert({ expr: 'amount <= limit' }, { amount: 3, limit: 5 })).toEqual(pass);
    expect(evaluateAssert({ expr: 'amount <= limit' }, { amount: 6, limit: 5 })).toEqual(fail('expr'));
    expect(evaluateAssert({ expr: 'amount >' }, { amount: 3 })).toEqual(fail('expr'));
    expect(evaluateAssert({ expr: 'globalThis.process.exit()' }, {})).toEqual(fail('expr'));
  });
});
