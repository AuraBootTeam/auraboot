/**
 * I18nText - Locale-aware text display component for i18n-enabled fields.
 *
 * For fields with `feature.i18nEnabled: true`, the backend stores translations
 * in companion fields ({fieldCode}_en_us, {fieldCode}_ja_jp, {fieldCode}_ko_kr).
 * This component reads the appropriate companion field based on the active locale
 * and falls back to the primary field when the companion value is empty.
 *
 * Usage:
 * ```tsx
 * <I18nText record={row} fieldCode="product_name" />
 * ```
 *
 * The component automatically uses the active locale from I18nContext.
 * Pass `locale` explicitly to override (useful for previews).
 *
 * @since 7.0.0
 */

import React from 'react';
import { useI18n } from '~/contexts/I18nContext';
import { resolveI18nValue } from '~/framework/meta/utils/i18nFieldHelper';

export interface I18nTextProps {
  /** The data record containing primary and companion locale fields */
  record: Record<string, unknown>;
  /** Primary field code (e.g. "product_name") */
  fieldCode: string;
  /**
   * Locale override (BCP-47, e.g. "en-US").
   * When omitted, the active locale from I18nContext is used.
   */
  locale?: string;
  /** CSS class names */
  className?: string;
  /** Fallback text when both companion and primary are empty */
  fallback?: string;
}

/**
 * Display the localized value of an i18n-enabled field from a record.
 * Reads companion fields (_en_us / _ja_jp / _ko_kr) based on active locale,
 * falling back to the primary field (zh-CN default).
 */
const I18nText: React.FC<I18nTextProps> = ({
  record,
  fieldCode,
  locale: localeProp,
  className,
  fallback = '-',
}) => {
  const { locale: contextLocale } = useI18n();
  const activeLocale = localeProp ?? contextLocale;

  const value = resolveI18nValue(record, fieldCode, activeLocale);

  if (value === null || value === undefined || value.trim() === '') {
    return <span className={className ?? 'text-gray-400'}>{fallback}</span>;
  }

  return <span className={className}>{value}</span>;
};

export default I18nText;
