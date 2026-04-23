/**
 * i18n工具 - 重新导出统一实现
 *
 * 此文件已重构为统一实现的导出层，不再包含任何i18n逻辑。
 * 所有i18n功能位于: app/meta/runtime/expression/i18n-renderer.ts
 */

import { useI18n } from '~/contexts/I18nContext';
import { useCallback } from 'react';
import {
  getLocalizedText as getLocalizedTextImpl,
  type LocalizedText,
} from '~/framework/meta/runtime/expression/i18n-renderer';

// 重新导出统一实现的类型和函数
export {
  getLocalizedText,
  translateArray,
  type LocalizedText,
  type TranslatableText,
  type TranslateFunction,
} from '~/framework/meta/runtime/expression/i18n-renderer';

/**
 * React Hook：自动获取 t 函数并提供翻译权限
 *
 * @returns 本地化文本处理函数
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
    (text: string | null | undefined) => {
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
    (text: SmartText, _fallback?: string) => {
      if (text === null || text === undefined) return '';
      if (typeof text === 'string') return lt(text);
      if (typeof text === 'object' && 'i18nKey' in text) {
        const i18nKey = (text as { i18nKey?: string; params?: Record<string, any> }).i18nKey;
        if (i18nKey) {
          return t(i18nKey, (text as { params?: Record<string, any> }).params);
        }
      }
      return getLocalizedTextImpl(text as LocalizedText, locale, t);
    },
    [lt, t, locale],
  );
}
