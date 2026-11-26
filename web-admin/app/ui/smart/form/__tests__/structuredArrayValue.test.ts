import { describe, expect, it } from 'vitest';
import { invalidStructuredArrayRow, readStructuredArray } from '../structuredArrayValue';

describe('structured array values', () => {
  it('rehydrates a stored JSON value and keeps dates and duplicate references in order', () => {
    const ordered = ['first', 'second', 'first'];
    expect(readStructuredArray({ type: 'jsonb', value: JSON.stringify(ordered) })).toEqual(ordered);
    expect(readStructuredArray(JSON.stringify([{ date: '2026-11-27' }]))).toEqual([
      { date: '2026-11-27' },
    ]);
  });

  it('rejects unselected references and accepts an empty optional list', () => {
    const schema = { item: { key: 'campaign', label: 'Campaign', required: true } };
    expect(invalidStructuredArrayRow(['some-id', ''], schema)).toBe(1);
    expect(invalidStructuredArrayRow([], schema)).toBeNull();
    expect(invalidStructuredArrayRow('{"old":"shape"}', schema)).toBe(0);
  });

  it('allows empty comparison values and rejects incomplete conditions', () => {
    const schema = { columns: [
      { key: 'field', label: 'Field', required: true },
      { key: 'op', label: 'Operator', required: true },
      { key: 'value', label: 'Value' },
    ] };
    expect(invalidStructuredArrayRow([{ field: 'email', op: 'eq', value: '' }], schema)).toBeNull();
    expect(invalidStructuredArrayRow([{ field: 'email', value: 'sample' }], schema)).toBe(0);
  });
});
