import { useCallback } from 'react';
import { useI18n } from '~/contexts/I18nContext';
import { getLocalizedText } from '~/utils/i18n';
import messages from './strategyStudio.i18n.json';

export type StrategyStudioTextKey = keyof typeof messages;

export function strategyStudioText(
  key: StrategyStudioTextKey,
  locale: string,
  params: Record<string, string | number> = {},
): string {
  return getLocalizedText(messages[key], locale).replace(/\{(\w+)\}/g, (token, name: string) =>
    Object.prototype.hasOwnProperty.call(params, name) ? String(params[name]) : token,
  );
}

export type StrategyStudioTextFn = (
  key: StrategyStudioTextKey,
  params?: Record<string, string | number>,
) => string;

export function useStrategyStudioText() {
  const { locale } = useI18n();
  const text = useCallback<StrategyStudioTextFn>(
    (key, params) => strategyStudioText(key, locale, params),
    [locale],
  );
  return { locale, text };
}
