import React, { createContext, useContext, useEffect, useState, type ReactNode } from 'react';
import { useAuth } from '~/contexts/AuthContext';
import { userPreferenceService } from '~/services/userPreferenceService';
import { tenantPreferenceService } from '~/services/tenantPreferenceService';
import {
  loadEffectiveDateTimeFormats,
  DEFAULT_DATE_TIME_FORMATS,
  type DateTimeFormatPreferences,
} from '~/services/dateTimeFormatService';

const TIMEZONE_KEY = 'ui.timezone';
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
  const { isAuthenticated } = useAuth();
  const [timezone, setTimezone] = useState<string>(() => {
    if (initialTimezone) return initialTimezone;
    if (typeof window !== 'undefined') {
      const cached = localStorage.getItem(EFFECTIVE_TIMEZONE_KEY);
      if (cached) return cached;
    }
    return ''; // Empty → useEffect will set real value after mount
  });
  const [formats, setFormats] = useState<DateTimeFormatPreferences>(DEFAULT_DATE_TIME_FORMATS);

  useEffect(() => {
    loadEffectiveTimezone(isAuthenticated).then(tz => {
      setTimezone(prev => (prev !== tz ? tz : prev));
    });
    loadEffectiveDateTimeFormats(isAuthenticated).then(setFormats);
  }, [isAuthenticated]);

  return (
    <TimezoneContext.Provider value={{ timezone, formats }}>{children}</TimezoneContext.Provider>
  );
}

export function useTimezone() {
  return useContext(TimezoneContext);
}

/**
 * Load effective timezone following the preference cascade:
 * user preference > tenant preference > browser default
 *
 * When not authenticated, skip API calls to avoid 401 errors and use browser default directly.
 */
async function loadEffectiveTimezone(isAuthenticated: boolean): Promise<string> {
  if (isAuthenticated) {
    try {
      const userTz = await userPreferenceService.get<string>(TIMEZONE_KEY);
      if (userTz) {
        persistTimezone(userTz);
        return userTz;
      }
    } catch {
      // fall through
    }

    try {
      const tenantTz = await tenantPreferenceService.get<string>(TIMEZONE_KEY);
      if (tenantTz) {
        persistTimezone(tenantTz);
        return tenantTz;
      }
    } catch {
      // fall through
    }
  }

  const browserTz = Intl.DateTimeFormat().resolvedOptions().timeZone;
  persistTimezone(browserTz);
  return browserTz;
}

function persistTimezone(tz: string) {
  localStorage.setItem(EFFECTIVE_TIMEZONE_KEY, tz);
  if (typeof document !== 'undefined') {
    document.cookie = `${EFFECTIVE_TIMEZONE_KEY}=${encodeURIComponent(tz)};path=/;max-age=${365 * 24 * 3600};SameSite=Lax`;
  }
}
