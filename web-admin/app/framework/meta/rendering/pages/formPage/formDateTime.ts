export const DATETIME_OFFSET_SUFFIX = /(?:Z|[+-]\d{2}:?\d{2})$/i;

export const DATETIME_LOCAL_INPUT = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(?::\d{2}(?:\.\d{1,3})?)?$/;

export const DATETIME_LOCAL_WITHOUT_SECONDS = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/;

export function isDatetimeDataType(dataType?: string): boolean {
  return String(dataType || '').toLowerCase() === 'datetime';
}

export function padDatePart(value: number, size = 2): string {
  return String(value).padStart(size, '0');
}

export function localOffsetForDateTimeInput(value: string): string {
  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) {
    return '';
  }
  const offsetMinutes = -parsed.getTimezoneOffset();
  const sign = offsetMinutes >= 0 ? '+' : '-';
  const absMinutes = Math.abs(offsetMinutes);
  return `${sign}${padDatePart(Math.floor(absMinutes / 60))}:${padDatePart(absMinutes % 60)}`;
}

export function normalizeDateTimePayloadValue(rawValue: any): any {
  if (rawValue === undefined || rawValue === null) return rawValue;
  if (typeof rawValue !== 'string') return rawValue;

  const trimmed = rawValue.trim();
  if (!trimmed) return null;
  if (DATETIME_OFFSET_SUFFIX.test(trimmed) || !DATETIME_LOCAL_INPUT.test(trimmed)) {
    return trimmed;
  }

  const withSeconds = DATETIME_LOCAL_WITHOUT_SECONDS.test(trimmed) ? `${trimmed}:00` : trimmed;
  const offset = localOffsetForDateTimeInput(trimmed);
  return offset ? `${withSeconds}${offset}` : trimmed;
}

export function normalizeDateTimeFormValue(rawValue: any): any {
  if (rawValue === undefined || rawValue === null || rawValue === '') return rawValue;
  if (typeof rawValue !== 'string') return rawValue;

  const trimmed = rawValue.trim();
  if (!trimmed || !DATETIME_OFFSET_SUFFIX.test(trimmed)) {
    return trimmed;
  }

  const parsed = new Date(trimmed);
  if (Number.isNaN(parsed.getTime())) {
    return trimmed;
  }

  const base =
    `${parsed.getFullYear()}-${padDatePart(parsed.getMonth() + 1)}-${padDatePart(parsed.getDate())}` +
    `T${padDatePart(parsed.getHours())}:${padDatePart(parsed.getMinutes())}`;
  if (parsed.getSeconds() === 0 && parsed.getMilliseconds() === 0) {
    return base;
  }
  const seconds = `:${padDatePart(parsed.getSeconds())}`;
  const milliseconds =
    parsed.getMilliseconds() > 0 ? `.${padDatePart(parsed.getMilliseconds(), 3)}` : '';
  return `${base}${seconds}${milliseconds}`;
}
