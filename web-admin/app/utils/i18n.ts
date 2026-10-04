/**
 * i18n utilities: re-export the canonical implementation
 *
 * This module re-exports the canonical implementation without duplicating i18n logic.
 * The implementation lives in framework/meta/runtime/expression/i18n-renderer.ts.
 */

import { useI18n } from '~/contexts/I18nContext';
import { useCallback } from 'react';
import {
  getLocalizedText as getLocalizedTextImpl,
  type LocalizedText,
} from '~/framework/meta/runtime/expression/i18n-renderer';

// Re-export the canonical types and functions.
export {
  getLocalizedText,
  translateArray,
  type LocalizedText,
  type TranslatableText,
  type TranslateFunction,
} from '~/framework/meta/runtime/expression/i18n-renderer';

/**
 * React hook that binds translation and locale from the current i18n context.
 *
 * @returns A locale-aware text resolver.
 *
 * @example
 * function MyComponent({ title }) {
 *   const lt = useLocalizedText();
 *   return <h1>{lt(title)}</h1>;
 * }
 */
export function useLocalizedText() {
  const { t, locale } = useI18n();

  return useCallback(
    (text: Parameters<typeof getLocalizedTextImpl>[0]) => {
      return getLocalizedTextImpl(text, locale, t);
    },
    [t, locale],
  );
}

export type SmartText =
  | string
  | LocalizedText
  | { i18nKey: string; params?: Record<string, any> }
  | null
  | undefined;

export function useSmartText() {
  const { t, locale } = useI18n();
  const lt = useLocalizedText();

  return useCallback(
    (text: SmartText, fallback?: string) => {
      if (text === null || text === undefined) return '';
      if (typeof text === 'string') {
        if (text.startsWith('$i18n:')) {
          const key = text.slice(6);
          const translated = t(key, undefined, fallback);
          return translated === key ? (fallback ?? '') : translated;
        }
        return lt(text);
      }
      if (typeof text === 'object' && 'i18nKey' in text) {
        const i18nKey = (text as { i18nKey?: string; params?: Record<string, any> }).i18nKey;
        if (i18nKey) {
          const translated = t(i18nKey, (text as { params?: Record<string, any> }).params, fallback);
          return translated === i18nKey ? (fallback ?? '') : translated;
        }
      }
      return getLocalizedTextImpl(text as LocalizedText, locale, t);
    },
    [lt, t, locale],
  );
}
