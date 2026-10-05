import { useCallback } from 'react';
import { useI18n } from '~/contexts/I18nContext';
import { getLocalizedText } from '~/utils/i18n';
import messages from './missionControl.i18n.json';

export type MissionControlTextKey = keyof typeof messages;

export function missionControlText(
  key: MissionControlTextKey,
  locale: string,
  params: Record<string, string | number> = {},
): string {
  return getLocalizedText(messages[key], locale).replace(/\{(\w+)\}/g, (token, name: string) =>
    Object.prototype.hasOwnProperty.call(params, name) ? String(params[name]) : token,
  );
}

export type MissionControlTextFn = (
  key: MissionControlTextKey,
  params?: Record<string, string | number>,
) => string;

export function useMissionControlText() {
  const { locale } = useI18n();
  const text = useCallback<MissionControlTextFn>(
    (key, params) => missionControlText(key, locale, params),
    [locale],
  );
  return { locale, text };
}
