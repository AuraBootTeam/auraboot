import { useCallback } from 'react';
import { getLocalizedText } from '~/utils/i18n';
import { useI18n } from '~/contexts/I18nContext';
import catalog from './PropertyEditor.i18n.json';

export function usePropertyEditorText() {
  const { locale, t } = useI18n();
  return useCallback(
    (key: keyof typeof catalog, params: Record<string, string | number> = {}) =>
      getLocalizedText(catalog[key], locale, t).replace(/\{(\w+)\}/g, (placeholder, name: string) =>
        Object.prototype.hasOwnProperty.call(params, name) ? String(params[name]) : placeholder,
      ),
    [locale, t],
  );
}
