import { describe, it, expect } from 'vitest';
import {
  encodeSorts,
  decodeSorts,
  areSortsEqual,
  encodeFilters,
  decodeFilters,
} from '../useListUrlState';
import type { SortConfig, ViewFilterConfig } from '~/framework/smart/types/savedView';

describe('encodeSorts', () => {
  it('encodes multiple sort configs to colon-separated string', () => {
    const sorts: SortConfig[] = [
      { fieldCode: 'name', direction: 'asc', priority: 1 },
      { fieldCode: 'price', direction: 'desc', priority: 2 },
    ];
    expect(encodeSorts(sorts)).toBe('name:asc,price:desc');
  });

  it('encodes single sort config', () => {
    const sorts: SortConfig[] = [{ fieldCode: 'created_at', direction: 'desc', priority: 1 }];
    expect(encodeSorts(sorts)).toBe('created_at:desc');
  });

  it('returns null for empty array', () => {
    expect(encodeSorts([])).toBeNull();
  });

  it('returns null for undefined/null input', () => {
    expect(encodeSorts(undefined as any)).toBeNull();
    expect(encodeSorts(null as any)).toBeNull();
  });
});

describe('decodeSorts', () => {
  it('decodes colon-separated string to SortConfig array', () => {
    const result = decodeSorts('name:asc,price:desc');
    expect(result).toEqual([
      { fieldCode: 'name', direction: 'asc', priority: 1 },
      { fieldCode: 'price', direction: 'desc', priority: 2 },
    ]);
  });

  it('decodes single sort entry', () => {
    const result = decodeSorts('amount:asc');
    expect(result).toEqual([{ fieldCode: 'amount', direction: 'asc', priority: 1 }]);
  });

  it('returns empty array for empty string', () => {
    expect(decodeSorts('')).toEqual([]);
  });

  it('returns empty array for null/undefined', () => {
    expect(decodeSorts(null)).toEqual([]);
    expect(decodeSorts(undefined)).toEqual([]);
  });

  it('skips malformed segments (missing direction)', () => {
    const result = decodeSorts('name:asc,bad_entry,price:desc');
    expect(result).toEqual([
      { fieldCode: 'name', direction: 'asc', priority: 1 },
      { fieldCode: 'price', direction: 'desc', priority: 2 },
    ]);
  });

  it('skips segments with invalid direction', () => {
    const result = decodeSorts('name:up');
    expect(result).toEqual([]);
  });
});

describe('areSortsEqual', () => {
  it('treats equivalent sort arrays as equal even when references and priorities differ', () => {
    const fromUrl: SortConfig[] = [{ fieldCode: 'page_key', direction: 'asc', priority: 1 }];
    const fromSavedView: SortConfig[] = [{ fieldCode: 'page_key', direction: 'asc', priority: 0 }];

    expect(fromUrl).not.toBe(fromSavedView);
    expect(areSortsEqual(fromUrl, fromSavedView)).toBe(true);
  });

  it('detects sort field or direction changes', () => {
    expect(
      areSortsEqual(
        [{ fieldCode: 'page_key', direction: 'asc', priority: 1 }],
        [{ fieldCode: 'updated_at', direction: 'asc', priority: 1 }],
      ),
    ).toBe(false);
    expect(
      areSortsEqual(
        [{ fieldCode: 'page_key', direction: 'asc', priority: 1 }],
        [{ fieldCode: 'page_key', direction: 'desc', priority: 1 }],
      ),
    ).toBe(false);
  });
});

describe('encodeFilters', () => {
  it('encodes ViewFilterConfig array to base64', () => {
    const filters: ViewFilterConfig[] = [
      { fieldCode: 'status', operator: 'eq', value: 'active' },
    ];
    const encoded = encodeFilters(filters);
    expect(encoded).toBeTruthy();
    // Verify it is valid base64 that round-trips
    const decoded = JSON.parse(atob(encoded!));
    expect(decoded).toEqual(filters);
  });

  it('returns null for empty array', () => {
    expect(encodeFilters([])).toBeNull();
  });

  it('returns null for undefined/null input', () => {
    expect(encodeFilters(undefined as any)).toBeNull();
    expect(encodeFilters(null as any)).toBeNull();
  });

  it('handles complex filter with multiple entries', () => {
    const filters: ViewFilterConfig[] = [
      { fieldCode: 'amount', operator: 'gte', value: 1000 },
      { fieldCode: 'status', operator: 'in', value: ['active', 'pending'], logic: 'and' },
    ];
    const encoded = encodeFilters(filters);
    expect(encoded).toBeTruthy();
    const decoded = JSON.parse(atob(encoded!));
    expect(decoded).toEqual(filters);
  });
});

describe('decodeFilters', () => {
  it('decodes base64 string to ViewFilterConfig array', () => {
    const original: ViewFilterConfig[] = [
      { fieldCode: 'status', operator: 'eq', value: 'draft' },
      { fieldCode: 'amount', operator: 'gt', value: 500 },
    ];
    const base64 = btoa(JSON.stringify(original));
    const result = decodeFilters(base64);
    expect(result).toEqual(original);
  });

  it('returns empty array for empty string', () => {
    expect(decodeFilters('')).toEqual([]);
  });

  it('returns empty array for null/undefined', () => {
    expect(decodeFilters(null)).toEqual([]);
    expect(decodeFilters(undefined)).toEqual([]);
  });

  it('returns empty array for malformed base64', () => {
    expect(decodeFilters('not-valid-base64!!!')).toEqual([]);
  });

  it('returns empty array for valid base64 of non-array JSON', () => {
    const base64 = btoa(JSON.stringify({ notAnArray: true }));
    expect(decodeFilters(base64)).toEqual([]);
  });

  it('returns empty array for valid base64 of non-JSON', () => {
    const base64 = btoa('hello world');
    expect(decodeFilters(base64)).toEqual([]);
  });
});

describe('round-trip encode/decode', () => {
  it('sort round-trip preserves data', () => {
    const sorts: SortConfig[] = [
      { fieldCode: 'name', direction: 'asc', priority: 1 },
      { fieldCode: 'updated_at', direction: 'desc', priority: 2 },
    ];
    const encoded = encodeSorts(sorts);
    const decoded = decodeSorts(encoded);
    expect(decoded).toEqual(sorts);
  });

  it('filter round-trip preserves data', () => {
    const filters: ViewFilterConfig[] = [
      { fieldCode: 'status', operator: 'eq', value: 'active' },
      { fieldCode: 'amount', operator: 'between', value: [100, 500], logic: 'and' },
    ];
    const encoded = encodeFilters(filters);
    const decoded = decodeFilters(encoded);
    expect(decoded).toEqual(filters);
  });

  it('empty sort round-trip', () => {
    expect(decodeSorts(encodeSorts([]))).toEqual([]);
  });

  it('empty filter round-trip', () => {
    expect(decodeFilters(encodeFilters([]))).toEqual([]);
  });
});
