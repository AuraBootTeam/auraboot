import { describe, it, expect } from 'vitest';
import { applyApiTabCondition, filterListTabDisplayData } from '../listFilterConditions';

describe('API list selected queue payload', () => {
  it('overrides a stale queue filter without losing pagination and other submitted fields', () => {
    const query = { page: 0, size: 20, bucket: 'all', keyword: 'resistor' };
    applyApiTabCondition(query, { fieldName: 'bucket', operator: 'EQ', value: 'pending' });
    expect(query).toEqual({ page: 0, size: 20, bucket: 'pending', keyword: 'resistor' });
    applyApiTabCondition(query, null);
    expect(query.bucket).toBe('pending');
  });
  it('does not silently broaden unsupported or malformed selected queues', () => {
    for (const condition of [
      { fieldName: 'bucket', operator: 'IN', value: ['a', 'b'] },
      { fieldName: 'bucket', operator: 'EQ', value: {} },
      { fieldName: '', operator: 'EQ', value: 'pending' },
    ]) expect(() => applyApiTabCondition({}, condition)).toThrow();
  });
});


describe('selected queue display ownership', () => {
  const records = [{ pid: 'a', state: 'closed' }, { pid: 'b', state: 'external_deleted' }];
  it('retains server-qualified API rows when the selected queue is absent from their projection', () => {
    const condition = { fieldName: 'bucket', operator: 'EQ', value: 'paused' };
    expect(filterListTabDisplayData(records, condition, true)).toBe(records);
    expect(filterListTabDisplayData(records, null, true)).toBe(records);
  });
  it('retains local filtering for ordinary dynamic lists and every supported equality/set operator', () => {
    expect(filterListTabDisplayData(records, { fieldName: 'state', operator: 'EQ', value: 'closed' }, false)).toEqual([records[0]]);
    expect(filterListTabDisplayData(records, { fieldName: 'state', operator: 'NEQ', value: 'closed' }, false)).toEqual([records[1]]);
    expect(filterListTabDisplayData(records, { fieldName: 'state', operator: 'IN', values: ['closed'] }, false)).toEqual([records[0]]);
    expect(filterListTabDisplayData(records, { fieldName: 'state', operator: 'NOT_IN', value: ['closed'] }, false)).toEqual([records[1]]);
    expect(filterListTabDisplayData(records, null, false)).toBe(records);
  });
});
