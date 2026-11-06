import { describe, it, expect } from 'vitest';
import { applyApiTabCondition } from '../listFilterConditions';

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
