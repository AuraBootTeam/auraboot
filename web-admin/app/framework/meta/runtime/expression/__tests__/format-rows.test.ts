import { describe, expect, it } from 'vitest';
import { businessFunctions as fn } from '../business-functions';
import { ExpressionParser } from '../parser';
import { createExpressionContext } from '../context';

describe('bounded row text formatting', () => {
  it('keeps each exact quantity and unit, including literal expression text in data', () => {
    const rows = [
      { name: '${record.secret}', qty: '12345678901234567890.000001', unit: 'kg' },
      { name: '<b>Other</b>', qty: '0.000000000001', unit: '箱' },
    ];
    const before = structuredClone(rows);
    expect(fn.FORMAT_ROWS(rows, '{name}: {qty} {unit}')).toEqual([
      '${record.secret}: 12345678901234567890.000001 kg',
      '<b>Other</b>: 0.000000000001 箱',
    ]);
    expect(rows).toEqual(before);
    expect(ExpressionParser.parse("${fn.JOIN(fn.FORMAT_ROWS(form.rows, '{name}: {qty} {unit}'), '\\n')}", createExpressionContext({ form: { rows } })))
      .toBe('${record.secret}: 12345678901234567890.000001 kg\n<b>Other</b>: 0.000000000001 箱');
  });

  it('rejects absent, non-scalar, unsafe or unbounded inputs without executing accessors', () => {
    const getter = { qty: '1' };
    Object.defineProperty(getter, 'name', { enumerable: true, get: () => { throw new Error('ACCESSOR_EXECUTED'); } });
    expect(() => fn.FORMAT_ROWS([getter], '{name}')).toThrow('Accessor row paths');
    for (const [rows, template] of [
      [[{}], '{missing}'], [[{ name: null }], '{name}'], [[{ name: {} }], '{name}'],
      [[{ name: 'a' }], '{constructor}'], [[{ name: 'a' }], '{name'],
      [Array.from({ length: 1001 }, () => ({ name: 'a' })), '{name}'],
      [[{ name: 'a' }], 'x'.repeat(2049)],
    ] as const) expect(() => fn.FORMAT_ROWS(rows, template)).toThrow();
    expect(fn.FORMAT_ROWS([], '{name}')).toEqual([]);
  });
});
