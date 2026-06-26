import React, { createContext, useContext, useEffect, useState, type ReactNode } from 'react';
import { useAuth } from '~/contexts/AuthContext';
import {
  DEFAULT_DATE_TIME_FORMATS,
  type DateTimeFormatPreferences,
} from '~/shared/services/dateTimeFormatService';
import { tenantPreferenceService } from '~/shared/services/tenantPreferenceService';

interface TenantDisplayPrefs {
  timezone?: string;
  datetimeFormat?: string;
}

const EFFECTIVE_TIMEZONE_KEY = 'effective-timezone';
const DEFAULT_TIMEZONE = 'Asia/Shanghai';

interface TimezoneContextValue {
  timezone: string;
  formats: DateTimeFormatPreferences;
}

const TimezoneContext = createContext<TimezoneContextValue>({
  timezone: DEFAULT_TIMEZONE,
  formats: DEFAULT_DATE_TIME_FORMATS,
});

export function TimezoneProvider({
  children,
  initialTimezone,
}: {
  children: ReactNode;
  initialTimezone?: string;
}) {
  const { preferences, isAuthenticated } = useAuth();

  const [timezone, setTimezone] = useState<string>(() => {
    if (initialTimezone) return initialTimezone;
    return DEFAULT_TIMEZONE;
  });

  const [formats, setFormats] = useState<DateTimeFormatPreferences>(DEFAULT_DATE_TIME_FORMATS);

  // Tenant-level display policy (System Preferences). Lower priority than the
  // user's personal preferences, higher than the browser default. Fetched once.
  const [tenantPrefs, setTenantPrefs] = useState<TenantDisplayPrefs | null>(null);

  useEffect(() => {
    if (!isAuthenticated) {
      setTenantPrefs({});
      return;
    }

    let cancelled = false;
    Promise.all([
      tenantPreferenceService.get<string>('ui.timezone'),
      tenantPreferenceService.get<string>('ui.datetime.format'),
    ])
      .then(([timezone, datetimeFormat]) => {
        if (cancelled) return;
        setTenantPrefs({
          timezone: timezone || undefined,
          datetimeFormat: datetimeFormat || undefined,
        });
      })
      .catch(() => {
        if (!cancelled) setTenantPrefs({});
      });
    return () => {
      cancelled = true;
    };
  }, [isAuthenticated]);

  useEffect(() => {
    const browserTz = typeof window !== 'undefined'
      ? Intl.DateTimeFormat().resolvedOptions().timeZone
      : DEFAULT_TIMEZONE;
    const cachedTz = typeof window !== 'undefined'
      ? localStorage.getItem(EFFECTIVE_TIMEZONE_KEY)
      : null;
    // Resolution chain: user personal preference → tenant policy → browser.
    const tz = preferences?.timezone || tenantPrefs?.timezone || cachedTz || browserTz || DEFAULT_TIMEZONE;
    setTimezone(tz);
    persistTimezone(tz);

    setFormats({
      date: preferences?.dateFormat || DEFAULT_DATE_TIME_FORMATS.date,
      datetime:
        preferences?.datetimeFormat ||
        tenantPrefs?.datetimeFormat ||
        DEFAULT_DATE_TIME_FORMATS.datetime,
      time: preferences?.timeFormat || DEFAULT_DATE_TIME_FORMATS.time,
    });
  }, [preferences, tenantPrefs]);

  return (
    <TimezoneContext.Provider value={{ timezone, formats }}>{children}</TimezoneContext.Provider>
  );
}

export function useTimezone() {
  return useContext(TimezoneContext);
}

function persistTimezone(tz: string) {
  if (typeof window === 'undefined') return;
  localStorage.setItem(EFFECTIVE_TIMEZONE_KEY, tz);
  if (typeof document !== 'undefined') {
    document.cookie = `${EFFECTIVE_TIMEZONE_KEY}=${encodeURIComponent(tz)};path=/;max-age=${365 * 24 * 3600};SameSite=Lax`;
  }
}
