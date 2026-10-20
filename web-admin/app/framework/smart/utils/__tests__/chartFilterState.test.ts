import { describe, expect, it } from 'vitest';
import { hasIncompleteChartFilters } from '../chartFilterState';

describe('chart filter draft completeness', () => {
  it.each([0, false, '0'])('keeps the valid filter value %s queryable', (value) => {
    expect(hasIncompleteChartFilters([{ field: 'amount', operator: 'eq', value }])).toBe(false);
  });
  it.each(['', ' ', null, undefined, []])('pauses an unfinished comparison value %s', (value) => {
    expect(hasIncompleteChartFilters([{ field: 'amount', operator: 'eq', value }])).toBe(true);
  });
  it('accepts null checks without a value and detects incomplete nested conditions', () => {
    expect(hasIncompleteChartFilters([{ field: 'amount', operator: 'is_null', value: null }])).toBe(
      false,
    );
    expect(
      hasIncompleteChartFilters([
        {
          field: '',
          operator: 'eq',
          value: '',
          children: [{ field: 'status', operator: 'eq', value: '' }],
        },
      ]),
    ).toBe(true);
    expect(hasIncompleteChartFilters([])).toBe(false);
  });
});
