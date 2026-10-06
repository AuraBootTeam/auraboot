import { useCallback } from 'react';
import { useI18n } from '~/contexts/I18nContext';
import { getLocalizedText } from '~/utils/i18n';
import messages from './modelDetail.i18n.json';

export type ModelDetailTextKey = keyof typeof messages;

export function modelDetailText(
  key: ModelDetailTextKey,
  locale: string,
  params: Record<string, string | number> = {},
): string {
  return getLocalizedText(messages[key], locale).replace(/\{(\w+)\}/g, (token, name: string) =>
    Object.prototype.hasOwnProperty.call(params, name) ? String(params[name]) : token,
  );
}

export type ModelDetailTextFn = (
  key: ModelDetailTextKey,
  params?: Record<string, string | number>,
) => string;

export function useModelDetailText() {
  const { locale } = useI18n();
  const text = useCallback<ModelDetailTextFn>(
    (key, params) => modelDetailText(key, locale, params),
    [locale],
  );
  return { locale, text };
}
