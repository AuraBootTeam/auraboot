import { useCallback } from 'react';
import { useI18n } from '~/contexts/I18nContext';
import { getLocalizedText } from '~/utils/i18n';
import messages from './executiveDashboard.i18n.json';

export type ExecutiveDashboardTextKey = keyof typeof messages;

export function executiveDashboardText(
  key: ExecutiveDashboardTextKey,
  locale: string,
  params: Record<string, string | number> = {},
): string {
  return getLocalizedText(messages[key], locale).replace(/\{(\w+)\}/g, (token, name: string) =>
    Object.prototype.hasOwnProperty.call(params, name) ? String(params[name]) : token,
  );
}

export type ExecutiveDashboardTextFn = (
  key: ExecutiveDashboardTextKey,
  params?: Record<string, string | number>,
) => string;

export function useExecutiveDashboardText() {
  const { locale } = useI18n();
  const text = useCallback<ExecutiveDashboardTextFn>(
    (key, params) => executiveDashboardText(key, locale, params),
    [locale],
  );
  return { locale, text };
}
