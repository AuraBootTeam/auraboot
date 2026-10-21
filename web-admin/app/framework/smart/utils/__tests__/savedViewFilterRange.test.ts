import { describe, expect, it } from 'vitest';
import type { ViewFilterConfig } from '../../types/savedView';
import { normalizeSavedViewFilterRange } from '../savedViewFilterRange';

describe('normalizeSavedViewFilterRange', () => {
  it('adapts frozen legacy ranges without changing storage and is idempotent', () => {
    const source: ViewFilterConfig = Object.freeze({
      fieldCode: 'updated_at', operator: 'between',
      value: Object.freeze({ start: '2026-10-14', end: '2026-10-21T23:59:59' }),
    });
    const normalized = normalizeSavedViewFilterRange(source);
    expect(normalized).toEqual({ ...source, value: ['2026-10-14', '2026-10-21T23:59:59'] });
    expect(source.value).toEqual({ start: '2026-10-14', end: '2026-10-21T23:59:59' });
    expect(normalizeSavedViewFilterRange(normalized)).toBe(normalized);
  });

  it('does not reinterpret non-range filters or unknown objects', () => {
    const expression: ViewFilterConfig = {
      fieldCode: 'owner', operator: 'in', value: { $currentDepartmentOwnerPids: true },
    };
    const unknown: ViewFilterConfig = { fieldCode: 'amount', operator: 'between', value: { bad: 1 } };
    expect(normalizeSavedViewFilterRange(expression)).toBe(expression);
    expect(normalizeSavedViewFilterRange(unknown)).toBe(unknown);
  });

  it('keeps zero as a real bound and leaves an empty range inactive', () => {
    expect(normalizeSavedViewFilterRange({
      fieldCode: 'amount', operator: 'between', value: { start: 0, end: '' },
    })).toEqual({ fieldCode: 'amount', operator: 'gte', value: 0 });
    expect(normalizeSavedViewFilterRange({
      fieldCode: 'amount', operator: 'between', value: { start: '', end: null },
    })).toEqual({ fieldCode: 'amount', operator: 'between', value: undefined });
  });
});
