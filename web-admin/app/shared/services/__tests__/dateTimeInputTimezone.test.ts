import { describe, expect, it } from 'vitest';
import { formatInTimezone, parseDateTimeInTimezone } from '../dateTimeFormatService';

describe('explicit-zone calendar input', () => {
  it('converts the tenant wall time without using the machine zone', () => {
    expect(parseDateTimeInTimezone('2026-10-30T15:45', 'America/New_York')).toBe('2026-10-30T19:45:00.000Z');
    expect(formatInTimezone('2026-10-30T19:45:00Z', 'YYYY-MM-DDTHH:mm', 'Asia/Shanghai')).toBe('2026-10-31T03:45');
  });
  it('rejects missing spring clock times and normalizes neither invalid dates nor empty values', () => {
    expect(parseDateTimeInTimezone('2026-03-08T02:30', 'America/New_York')).toBe('');
    expect(parseDateTimeInTimezone('2026-02-30T09:00', 'Asia/Shanghai')).toBe('');
    expect(parseDateTimeInTimezone('', 'Asia/Shanghai')).toBe('');
  });
  it('chooses the earlier occurrence of a repeated fall clock time', () => {
    expect(parseDateTimeInTimezone('2026-11-01T01:30', 'America/New_York')).toBe('2026-11-01T05:30:00.000Z');
  });
});
