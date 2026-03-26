import { useCallback } from 'react';
import { useI18n } from '~/contexts/I18nContext';
import { useTimezone } from '~/contexts/TimezoneContext';

const DEFAULT_LOCALE = 'zh-CN';
const DEFAULT_TIMEZONE = 'Asia/Shanghai';

/**
 * Hook for locale-aware number formatting using Intl.NumberFormat.
 *
 * Returns a `formatNumber` function that safely handles null/undefined values
 * and falls back gracefully on invalid input.
 */
export function useNumberFormat() {
  const { locale } = useI18n();

  const formatNumber = useCallback(
    (value: number | null | undefined, options?: Intl.NumberFormatOptions): string => {
      if (value == null) return '—';
      try {
        return new Intl.NumberFormat(locale || DEFAULT_LOCALE, options).format(value);
      } catch {
        return String(value);
      }
    },
    [locale],
  );

  return { formatNumber };
}

/**
 * Hook for locale-aware and timezone-aware date formatting using Intl.DateTimeFormat.
 *
 * Returns a `formatDate` function that accepts a string, Date, or number value.
 * Falls back gracefully on invalid dates.
 *
 * Note: For display consistency with the DateTime component (which uses dayjs),
 * prefer `DateTime` for rendering date cells; use this hook for programmatic
 * formatting (e.g. export labels, tooltip text, computed strings).
 */
export function useDateFormat() {
  const { locale } = useI18n();
  const { timezone } = useTimezone();

  const formatDate = useCallback(
    (
      value: string | number | Date | null | undefined,
      options?: Intl.DateTimeFormatOptions,
    ): string => {
      if (value == null) return '—';
      try {
        const date = value instanceof Date ? value : new Date(value);
        if (isNaN(date.getTime())) return String(value);
        return new Intl.DateTimeFormat(locale || DEFAULT_LOCALE, {
          timeZone: timezone || DEFAULT_TIMEZONE,
          ...options,
        }).format(date);
      } catch {
        return String(value);
      }
    },
    [locale, timezone],
  );

  return { formatDate };
}

/**
 * Hook for locale-aware currency formatting using Intl.NumberFormat with
 * `style: 'currency'`.
 *
 * Returns a `formatCurrency` function. The `currency` parameter defaults to
 * `'cny'`. Pass any ISO 4217 currency code to override.
 */
export function useCurrencyFormat() {
  const { locale } = useI18n();

  const formatCurrency = useCallback(
    (
      value: number | null | undefined,
      currency: string = 'cny',
      options?: Omit<Intl.NumberFormatOptions, 'style' | 'currency'>,
    ): string => {
      if (value == null) return '—';
      try {
        return new Intl.NumberFormat(locale || DEFAULT_LOCALE, {
          style: 'currency',
          currency,
          minimumFractionDigits: 2,
          maximumFractionDigits: 2,
          ...options,
        }).format(value);
      } catch {
        return `${currency} ${value}`;
      }
    },
    [locale],
  );

  return { formatCurrency };
}
