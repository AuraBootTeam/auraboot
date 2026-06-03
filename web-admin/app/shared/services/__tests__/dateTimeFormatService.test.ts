import { describe, it, expect } from 'vitest';
import { formatInTimezone } from '~/shared/services/dateTimeFormatService';

describe('formatInTimezone', () => {
  it('converts a UTC ISO string with offset into the target timezone', () => {
    // 03:08 UTC == 11:08 Beijing (UTC+8)
    expect(
      formatInTimezone('2026-06-03T03:08:04.030+00:00', 'YYYY-MM-DD HH:mm:ss', 'Asia/Shanghai'),
    ).toBe('2026-06-03 11:08:04');
  });

  it('converts a UTC ISO string with Z suffix into the target timezone', () => {
    expect(
      formatInTimezone('2026-06-03T03:08:00Z', 'YYYY-MM-DD HH:mm:ss', 'Asia/Shanghai'),
    ).toBe('2026-06-03 11:08:00');
  });

  it('treats a naive (no-offset) timestamp as UTC then converts', () => {
    // Backend may emit "2026-06-03 03:08:00" — must be read as UTC, not local
    expect(
      formatInTimezone('2026-06-03 03:08:00', 'YYYY-MM-DD HH:mm:ss', 'Asia/Shanghai'),
    ).toBe('2026-06-03 11:08:00');
  });

  it('handles a timezone that crosses the date boundary', () => {
    // 03:08 UTC == 23:08 previous day in New York (EDT, UTC-4 in June)
    expect(
      formatInTimezone('2026-06-03T03:08:04Z', 'YYYY-MM-DD HH:mm:ss', 'America/New_York'),
    ).toBe('2026-06-02 23:08:04');
  });

  it('returns empty string for null/undefined/empty', () => {
    expect(formatInTimezone(null, 'YYYY-MM-DD', 'Asia/Shanghai')).toBe('');
    expect(formatInTimezone(undefined, 'YYYY-MM-DD', 'Asia/Shanghai')).toBe('');
    expect(formatInTimezone('', 'YYYY-MM-DD', 'Asia/Shanghai')).toBe('');
  });

  it('returns the original string when the value is not a valid date', () => {
    expect(formatInTimezone('not-a-date', 'YYYY-MM-DD', 'Asia/Shanghai')).toBe('not-a-date');
  });

  it('falls back to UTC formatting when timezone is missing', () => {
    expect(formatInTimezone('2026-06-03T03:08:00Z', 'YYYY-MM-DD HH:mm:ss')).toBe(
      '2026-06-03 03:08:00',
    );
  });

  it('does not throw and falls back to UTC on an invalid timezone id', () => {
    expect(
      formatInTimezone('2026-06-03T03:08:00Z', 'YYYY-MM-DD HH:mm:ss', 'Not/AZone'),
    ).toBe('2026-06-03 03:08:00');
  });
});
