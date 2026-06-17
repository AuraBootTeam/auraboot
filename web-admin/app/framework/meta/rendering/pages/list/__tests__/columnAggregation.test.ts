import { describe, expect, it } from 'vitest';
import {
  aggregateColumn,
  coerceNumeric,
  formatAggregateValue,
  type AggregateKind,
} from '../columnAggregation';

describe('coerceNumeric', () => {
  it('passes through finite numbers', () => {
    expect(coerceNumeric(42)).toBe(42);
    expect(coerceNumeric(0)).toBe(0);
    expect(coerceNumeric(-3.5)).toBe(-3.5);
  });

  it('parses plain numeric strings', () => {
    expect(coerceNumeric('12')).toBe(12);
    expect(coerceNumeric('12.5')).toBe(12.5);
    expect(coerceNumeric('-7')).toBe(-7);
  });

  it('tolerates grouped/currency strings like "12,345.67" and "¥1,200"', () => {
    expect(coerceNumeric('12,345.67')).toBeCloseTo(12345.67, 5);
    expect(coerceNumeric('¥1,200')).toBe(1200);
    expect(coerceNumeric('$1,000.50')).toBeCloseTo(1000.5, 5);
    expect(coerceNumeric(' 3,000 ')).toBe(3000);
  });

  it('returns null for non-numeric / empty / nullish values', () => {
    expect(coerceNumeric(null)).toBeNull();
    expect(coerceNumeric(undefined)).toBeNull();
    expect(coerceNumeric('')).toBeNull();
    expect(coerceNumeric('   ')).toBeNull();
    expect(coerceNumeric('abc')).toBeNull();
    expect(coerceNumeric(NaN)).toBeNull();
    expect(coerceNumeric(Infinity)).toBeNull();
    expect(coerceNumeric({})).toBeNull();
    expect(coerceNumeric([])).toBeNull();
  });

  it('coerces booleans to null (not 0/1) to avoid silent miscounts', () => {
    expect(coerceNumeric(true)).toBeNull();
    expect(coerceNumeric(false)).toBeNull();
  });
});

describe('aggregateColumn — sum', () => {
  it('sums numeric values across rows', () => {
    const rows = [{ amount: 10 }, { amount: 20 }, { amount: 30 }];
    expect(aggregateColumn(rows, 'amount', 'sum')).toBe(60);
  });

  it('skips non-numeric values when summing', () => {
    const rows = [{ amount: 10 }, { amount: 'n/a' }, { amount: '20' }, { amount: null }];
    expect(aggregateColumn(rows, 'amount', 'sum')).toBe(30);
  });

  it('parses grouped/currency strings before summing', () => {
    const rows = [{ amount: '1,000.50' }, { amount: '¥2,000' }];
    expect(aggregateColumn(rows, 'amount', 'sum')).toBeCloseTo(3000.5, 5);
  });

  it('returns null when no numeric values present', () => {
    const rows = [{ amount: 'x' }, { amount: null }, { amount: undefined }];
    expect(aggregateColumn(rows, 'amount', 'sum')).toBeNull();
  });

  it('returns null for empty rows', () => {
    expect(aggregateColumn([], 'amount', 'sum')).toBeNull();
  });

  it('keeps floating-point precision within tolerance', () => {
    const rows = [{ v: 0.1 }, { v: 0.2 }];
    expect(aggregateColumn(rows, 'v', 'sum')).toBeCloseTo(0.3, 10);
  });
});

describe('aggregateColumn — avg', () => {
  it('averages only numeric (non-null) values', () => {
    const rows = [{ score: 10 }, { score: 20 }, { score: null }, { score: 'x' }];
    expect(aggregateColumn(rows, 'score', 'avg')).toBe(15);
  });

  it('returns null when there are no numeric values to average', () => {
    expect(aggregateColumn([{ score: null }], 'score', 'avg')).toBeNull();
    expect(aggregateColumn([], 'score', 'avg')).toBeNull();
  });
});

describe('aggregateColumn — count', () => {
  it('counts non-null values only (not blanks/undefined)', () => {
    const rows = [{ v: 1 }, { v: 0 }, { v: null }, { v: undefined }, { v: '' }, { v: 'x' }];
    // 1, 0, and 'x' are non-null/non-empty → count = 3
    expect(aggregateColumn(rows, 'v', 'count')).toBe(3);
  });

  it('returns 0 (not null) for empty rows so the footer shows a concrete count', () => {
    expect(aggregateColumn([], 'v', 'count')).toBe(0);
  });

  it('counts non-numeric non-empty values too (count is type-agnostic)', () => {
    const rows = [{ name: 'Ada' }, { name: 'Bo' }, { name: null }];
    expect(aggregateColumn(rows, 'name', 'count')).toBe(2);
  });
});

describe('aggregateColumn — min/max', () => {
  it('finds the min of numeric values, skipping non-numeric', () => {
    const rows = [{ v: 5 }, { v: '2' }, { v: 'x' }, { v: 9 }];
    expect(aggregateColumn(rows, 'v', 'min')).toBe(2);
  });

  it('finds the max of numeric values', () => {
    const rows = [{ v: 5 }, { v: '2' }, { v: 'x' }, { v: 9 }];
    expect(aggregateColumn(rows, 'v', 'max')).toBe(9);
  });

  it('handles negatives', () => {
    const rows = [{ v: -5 }, { v: -1 }, { v: -10 }];
    expect(aggregateColumn(rows, 'v', 'min')).toBe(-10);
    expect(aggregateColumn(rows, 'v', 'max')).toBe(-1);
  });

  it('returns null for min/max with no numeric values', () => {
    expect(aggregateColumn([{ v: 'x' }], 'v', 'min')).toBeNull();
    expect(aggregateColumn([{ v: 'x' }], 'v', 'max')).toBeNull();
    expect(aggregateColumn([], 'v', 'max')).toBeNull();
  });
});

describe('aggregateColumn — invalid kind', () => {
  it('returns null for an unknown aggregate kind', () => {
    expect(aggregateColumn([{ v: 1 }], 'v', 'median' as AggregateKind)).toBeNull();
  });
});

describe('formatAggregateValue', () => {
  it('formats a plain number with thousands grouping', () => {
    expect(formatAggregateValue(1234567.5, undefined, 'en')).toBe('1,234,567.5');
  });

  it('formats currency columns with the column currency code', () => {
    const out = formatAggregateValue(3000.5, { valueType: 'currency', currencyCode: 'USD' }, 'en');
    expect(out).toContain('3,000.50');
    expect(out).toMatch(/\$|USD/);
  });

  it('renders count as an integer (no fraction)', () => {
    expect(formatAggregateValue(3, { kind: 'count' }, 'en')).toBe('3');
  });

  it('returns an em dash for null/undefined', () => {
    expect(formatAggregateValue(null, undefined, 'en')).toBe('—');
    expect(formatAggregateValue(undefined, undefined, 'en')).toBe('—');
  });
});
