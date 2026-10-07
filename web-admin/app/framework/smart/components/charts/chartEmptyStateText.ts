import { useCallback } from 'react';
import { useI18n } from '~/contexts/I18nContext';
import { getLocalizedText } from '~/utils/i18n';
import messages from './chartEmptyState.i18n.json';

export type ChartEmptyStateTextKey = keyof typeof messages;

export function chartEmptyStateText(
  key: ChartEmptyStateTextKey,
  locale: string,
  params: Record<string, string | number> = {},
): string {
  return getLocalizedText(messages[key], locale).replace(/\{(\w+)\}/g, (token, name: string) =>
    Object.prototype.hasOwnProperty.call(params, name) ? String(params[name]) : token,
  );
}

export type ChartEmptyStateTextFn = (
  key: ChartEmptyStateTextKey,
  params?: Record<string, string | number>,
) => string;

export function useChartEmptyStateText() {
  const { locale } = useI18n();
  const text = useCallback<ChartEmptyStateTextFn>(
    (key, params) => chartEmptyStateText(key, locale, params),
    [locale],
  );
  return { locale, text };
}
