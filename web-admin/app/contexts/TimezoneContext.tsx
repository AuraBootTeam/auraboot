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

interface TimezoneContextValue {
  timezone: string;
  formats: DateTimeFormatPreferences;
}

const TimezoneContext = createContext<TimezoneContextValue>({
  timezone: Intl.DateTimeFormat().resolvedOptions().timeZone,
  formats: DEFAULT_DATE_TIME_FORMATS,
});

export function TimezoneProvider({
  children,
  initialTimezone,
}: {
  children: ReactNode;
  initialTimezone?: string;
}) {
  const { preferences } = useAuth();

  const [timezone, setTimezone] = useState<string>(() => {
    if (initialTimezone) return initialTimezone;
    if (typeof window !== 'undefined') {
      const cached = localStorage.getItem(EFFECTIVE_TIMEZONE_KEY);
      if (cached) return cached;
    }
    return Intl.DateTimeFormat().resolvedOptions().timeZone;
  });

  const [formats, setFormats] = useState<DateTimeFormatPreferences>(DEFAULT_DATE_TIME_FORMATS);

  // Tenant-level display policy (System Preferences). Lower priority than the
  // user's personal preferences, higher than the browser default. Fetched once.
  const [tenantPrefs, setTenantPrefs] = useState<TenantDisplayPrefs | null>(null);

  useEffect(() => {
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
  }, []);

  useEffect(() => {
    const browserTz = Intl.DateTimeFormat().resolvedOptions().timeZone;
    // Resolution chain: user personal preference → tenant policy → browser.
    const tz = preferences?.timezone || tenantPrefs?.timezone || browserTz;
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
  localStorage.setItem(EFFECTIVE_TIMEZONE_KEY, tz);
  if (typeof document !== 'undefined') {
    document.cookie = `${EFFECTIVE_TIMEZONE_KEY}=${encodeURIComponent(tz)};path=/;max-age=${365 * 24 * 3600};SameSite=Lax`;
  }
}
