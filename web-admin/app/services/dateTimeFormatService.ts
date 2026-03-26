import { tenantPreferenceService } from '~/services/tenantPreferenceService';
import { userPreferenceService } from '~/services/userPreferenceService';

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

const DATE_KEY = 'ui.date.format';
const DATETIME_KEY = 'ui.datetime.format';
const TIME_KEY = 'ui.time.format';

export async function loadEffectiveDateTimeFormats(
  isAuthenticated = true,
): Promise<DateTimeFormatPreferences> {
  if (!isAuthenticated) {
    return { ...DEFAULT_DATE_TIME_FORMATS };
  }

  const [userDate, userDateTime, userTime, tenantDate, tenantDateTime, tenantTime] =
    await Promise.all([
      userPreferenceService.get<string>(DATE_KEY),
      userPreferenceService.get<string>(DATETIME_KEY),
      userPreferenceService.get<string>(TIME_KEY),
      tenantPreferenceService.get<string>(DATE_KEY),
      tenantPreferenceService.get<string>(DATETIME_KEY),
      tenantPreferenceService.get<string>(TIME_KEY),
    ]);

  return {
    date: pickFormat(userDate, tenantDate, DEFAULT_DATE_TIME_FORMATS.date),
    datetime: pickFormat(userDateTime, tenantDateTime, DEFAULT_DATE_TIME_FORMATS.datetime),
    time: pickFormat(userTime, tenantTime, DEFAULT_DATE_TIME_FORMATS.time),
  };
}

function pickFormat(
  userValue: string | null,
  tenantValue: string | null,
  fallback: string,
): string {
  const normalizedUserValue = normalizeFormat(userValue);
  if (normalizedUserValue) {
    return normalizedUserValue;
  }
  const normalizedTenantValue = normalizeFormat(tenantValue);
  if (normalizedTenantValue) {
    return normalizedTenantValue;
  }
  return fallback;
}

function normalizeFormat(format: string | null | undefined): string | null {
  if (!format) return null;
  const trimmed = format.trim();
  return trimmed.length > 0 ? trimmed : null;
}
