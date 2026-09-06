import { describe, expect, it } from 'vitest';
import { normalizePayloadValue } from '../FormPageContent';

// Contract alignment with the platform's PayloadTemporalNormalizer: the backend
// rejects offset-less datetimes ("Expected: ISO-8601 datetime with offset"),
// so the form layer MUST serialize datetime-local input values with an offset.
// This test locks that contract (the Yangzhou gate C-1 / DEFECT #8 class).

describe('normalizePayloadValue — datetime serialization contract', () => {
  it('appends seconds and the local offset to a datetime-local value', () => {
    const out = normalizePayloadValue('2026-09-06T09:00', 'datetime') as string;
    expect(out.startsWith('2026-09-06T09:00:00')).toBe(true);
    expect(out).toMatch(/[+-]\d{2}:\d{2}$/);
  });

  it('passes through values that already carry an offset', () => {
    const withOffset = '2026-09-06T09:00:00+08:00';
    expect(normalizePayloadValue(withOffset, 'datetime')).toBe(withOffset);
    const withZ = '2026-09-06T09:00:00Z';
    expect(normalizePayloadValue(withZ, 'datetime')).toBe(withZ);
  });

  it('only the datetime dataType is normalized (timestamp passes raw — frontend set)', () => {
    // the frontend's isDatetimeDataType matches exactly 'datetime'; the platform
    // normalizer also lists timestamp/localdatetime — a noted contract asymmetry
    expect(normalizePayloadValue('2026-09-06T09:00', 'timestamp')).toBe('2026-09-06T09:00');
  });

  it('empty values become null (cleared field, not a parse error)', () => {
    expect(normalizePayloadValue('', 'datetime')).toBeNull();
  });

  it('non-datetime dataTypes pass through untouched', () => {
    expect(normalizePayloadValue('raw-text', 'string')).toBe('raw-text');
  });
});
