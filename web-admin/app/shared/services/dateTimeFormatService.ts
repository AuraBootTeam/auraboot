import dayjs from 'dayjs';
import utc from 'dayjs/plugin/utc';
import timezone from 'dayjs/plugin/timezone';

dayjs.extend(utc);
dayjs.extend(timezone);

export interface DateTimeFormatPreferences {
  date: string;
  datetime: string;
  time: string;
}

export const DEFAULT_DATE_TIME_FORMATS: DateTimeFormatPreferences = {
  date: 'YYYY-MM-DD',
  datetime: 'YYYY-MM-DD HH:mm:ss',
  time: 'HH:mm:ss',
};

/**
 * Format a backend datetime value for display in a given timezone.
 *
 * The backend serializes all datetimes as UTC (spring.jackson.time-zone: UTC),
 * so the raw value is always an absolute UTC instant. This helper is the single
 * canonical place that converts that UTC instant into the effective display
 * timezone. ALL datetime display paths (cell renderers, sub-tables, detail
 * fields, custom panels) must route through this function instead of slicing
 * the raw string or calling dayjs(value) without a timezone.
 *
 * @param value     A UTC datetime (ISO string with offset/Z, naive timestamp, epoch, or Date).
 *                  Naive timestamps without an offset are interpreted as UTC.
 * @param format    A dayjs format string (e.g. "YYYY-MM-DD HH:mm:ss").
 * @param timeZone  IANA timezone id (e.g. "Asia/Shanghai"). Falls back to UTC
 *                  when missing or invalid.
 * @returns The formatted string, '' for null/empty, or the original string when
 *          the value is not a valid date.
 */
export function formatInTimezone(
  value: string | number | Date | null | undefined,
  format: string,
  timeZone?: string,
): string {
  if (value === null || value === undefined || value === '') {
    return '';
  }
  const parsed = dayjs.utc(value);
  if (!parsed.isValid()) {
    return String(value);
  }
  if (!timeZone) {
    return parsed.format(format);
  }
  try {
    return parsed.tz(timeZone).format(format);
  } catch {
    // Invalid IANA zone id — degrade to UTC rather than crash the render path.
    return parsed.format(format);
  }
}

export type TemporalType = 'date' | 'datetime' | 'time';

const ISO_DATETIME_RE = /^\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}/;

/**
 * Decide whether a column/value should be rendered as a temporal value, and of
 * which kind. Mirrors the inference used by the list cell renderer so every
 * display surface classifies datetimes the same way:
 *   1. explicit column.valueType (datetime/date/time)
 *   2. date-bearing field suffix or ISO-8601 timestamp value
 *   3. time-only field-name suffix (_time)
 * Returns null when the value is not temporal.
 */
export function resolveTemporalType(
  field: string | undefined,
  valueType: string | undefined,
  value: unknown,
): TemporalType | null {
  const declaredType = valueType?.toLowerCase();
  if (declaredType === 'datetime' || declaredType === 'date' || declaredType === 'time') {
    return declaredType;
  }
  const f = field || '';
  if (f.endsWith('_at')) return 'datetime';
  if (f.endsWith('_date')) return 'date';
  if (typeof value === 'string' && ISO_DATETIME_RE.test(value)) {
    return 'datetime';
  }
  if (f.endsWith('_time')) return 'time';
  return null;
}

/**
 * Resolve the dayjs format string for a temporal value, honoring an explicit
 * column-level format first, then the user/tenant format preferences, then the
 * built-in defaults.
 */
export function resolveTemporalFormat(
  type: TemporalType,
  formats?: Partial<DateTimeFormatPreferences>,
  explicitFormat?: string,
): string {
  if (explicitFormat && !['date', 'datetime', 'time'].includes(explicitFormat.toLowerCase()) && /Y{2,4}|M{1,4}|D{1,4}|H{1,2}|m{1,2}|s{1,2}/.test(explicitFormat)) {
    return explicitFormat;
  }
  return formats?.[type] || DEFAULT_DATE_TIME_FORMATS[type];
}

/** Convert a minute-precision wall time in an explicit zone; gaps reject and folds choose the earlier instant. */
export function parseDateTimeInTimezone(value: string, timeZone: string): string {
  if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/.test(value)) return '';
  const wall = Date.parse(`${value}:00Z`);
  if (!Number.isFinite(wall)) return '';
  const offsets = new Set<number>();
  for (let hour = -36; hour <= 36; hour += 6) {
    const sample = wall + hour * 3_600_000;
    const rendered = formatInTimezone(sample, 'YYYY-MM-DDTHH:mm', timeZone);
    offsets.add(Date.parse(`${rendered}:00Z`) - sample);
  }
  const candidates = [...offsets].map((offset) => wall - offset)
    .filter((instant) => formatInTimezone(instant, 'YYYY-MM-DDTHH:mm', timeZone) === value);
  return candidates.length ? new Date(Math.min(...candidates)).toISOString() : '';
}
