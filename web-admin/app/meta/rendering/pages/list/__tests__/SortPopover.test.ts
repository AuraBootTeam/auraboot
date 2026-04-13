import { describe, it, expect } from 'vitest';
import { getDirectionLabels } from '../SortPopover';

describe('getDirectionLabels', () => {
  it('returns A-Z / Z-A for text fields (default)', () => {
    const labels = getDirectionLabels(undefined);
    expect(labels).toEqual({ asc: 'A-Z', desc: 'Z-A' });
  });

  it('returns A-Z / Z-A for unknown valueType', () => {
    const labels = getDirectionLabels('boolean');
    expect(labels).toEqual({ asc: 'A-Z', desc: 'Z-A' });
  });

  it('returns 1-9 / 9-1 for number fields', () => {
    expect(getDirectionLabels('number')).toEqual({ asc: '1-9', desc: '9-1' });
  });

  it('returns 1-9 / 9-1 for currency fields', () => {
    expect(getDirectionLabels('currency')).toEqual({ asc: '1-9', desc: '9-1' });
  });

  it('returns 1-9 / 9-1 for integer fields', () => {
    expect(getDirectionLabels('integer')).toEqual({ asc: '1-9', desc: '9-1' });
  });

  it('returns 1-9 / 9-1 for decimal fields', () => {
    expect(getDirectionLabels('decimal')).toEqual({ asc: '1-9', desc: '9-1' });
  });

  it('returns 1-9 / 9-1 for percent fields', () => {
    expect(getDirectionLabels('percent')).toEqual({ asc: '1-9', desc: '9-1' });
  });

  it('returns Old-New / New-Old for date fields', () => {
    expect(getDirectionLabels('date')).toEqual({ asc: 'Old-New', desc: 'New-Old' });
  });

  it('returns Old-New / New-Old for datetime fields', () => {
    expect(getDirectionLabels('datetime')).toEqual({ asc: 'Old-New', desc: 'New-Old' });
  });

  it('returns Old-New / New-Old for time fields', () => {
    expect(getDirectionLabels('time')).toEqual({ asc: 'Old-New', desc: 'New-Old' });
  });
});
