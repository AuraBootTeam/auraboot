/**
 * i18nFieldHelper - Utilities for user-content i18n field resolution.
 *
 * When a DSL field has `feature.i18nEnabled: true`, the backend auto-creates
 * locale companion fields during model publish:
 *   - {fieldCode}_en_us  → English (US)
 *   - {fieldCode}_ja_jp  → Japanese
 *   - {fieldCode}_ko_kr  → Korean
 *
 * The primary field stores the default locale value (zh-CN).
 * These helpers resolve the correct companion field code and value
 * based on the active locale, with fallback to the primary field.
 *
 * @since 7.0.0
 */

/** Map of BCP-47 locale tag → DB field code suffix */
export const I18N_LOCALE_SUFFIXES: Record<string, string> = {
  'en-US': '_en_us',
  'ja-JP': '_ja_jp',
  'ko-KR': '_ko_kr',
};

/** All supported non-default locales in a stable order */
export const I18N_SUPPORTED_LOCALES = ['en-US', 'ja-JP', 'ko-KR'] as const;
export type I18nSupportedLocale = (typeof I18N_SUPPORTED_LOCALES)[number];

/** Locale display labels shown in the tab UI */
export const I18N_LOCALE_LABELS: Record<string, string> = {
  'zh-CN': '中文',
  'en-US': 'English',
  'ja-JP': '日本語',
  'ko-KR': '한국어',
};

/**
 * Return the companion field code for the given source field and locale.
 * Returns the source field code unchanged for zh-CN or unknown locales.
 *
 * @example
 * getI18nFieldCode('product_name', 'en-US')  // → 'product_name_en_us'
 * getI18nFieldCode('product_name', 'zh-CN')  // → 'product_name'
 * getI18nFieldCode('product_name', 'fr-FR')  // → 'product_name' (not supported, fallback)
 */
export function getI18nFieldCode(fieldCode: string, locale: string): string {
  if (!locale || locale.startsWith('zh')) return fieldCode;
  const suffix = I18N_LOCALE_SUFFIXES[locale];
  return suffix ? `${fieldCode}${suffix}` : fieldCode;
}

/**
 * Resolve the best-matching localized value from a record for the given locale.
 * Falls back to the primary field if the companion field is null/empty/missing.
 *
 * @param record    Data record object (field code → value)
 * @param fieldCode Primary field code (e.g. "product_name")
 * @param locale    Active BCP-47 locale (e.g. "en-US")
 * @returns         Localized string value, or null if nothing found
 *
 * @example
 * resolveI18nValue(row, 'product_name', 'en-US')
 * // → row.product_name_en_us if non-empty, else row.product_name
 */
export function resolveI18nValue(
  record: Record<string, unknown> | null | undefined,
  fieldCode: string,
  locale: string,
): string | null {
  if (!record) return null;

  const companionCode = getI18nFieldCode(fieldCode, locale);
  if (companionCode !== fieldCode) {
    const companionValue = record[companionCode];
    if (companionValue != null && String(companionValue).trim() !== '') {
      return String(companionValue);
    }
  }

  const primaryValue = record[fieldCode];
  return primaryValue != null ? String(primaryValue) : null;
}

/**
 * Build a partial record patch for all locale companion fields of a given
 * primary field, given a locale→value map.
 *
 * Useful when saving i18n form data: the form contains one value per locale,
 * and this function converts them into the flat key→value patch to merge into
 * the submit payload.
 *
 * @example
 * buildI18nPatch('product_name', { 'zh-CN': '产品名称', 'en-US': 'Product Name', 'ja-JP': '製品名' })
 * // → { product_name: '产品名称', product_name_en_us: 'Product Name', product_name_ja_jp: '製品名' }
 */
export function buildI18nPatch(
  fieldCode: string,
  localeValues: Partial<Record<string, string>>,
): Record<string, string | null> {
  const patch: Record<string, string | null> = {};

  for (const [locale, value] of Object.entries(localeValues)) {
    const targetCode = getI18nFieldCode(fieldCode, locale);
    patch[targetCode] = value ?? null;
  }

  return patch;
}

/**
 * Extract all locale values for an i18n field from a flat record.
 * Returns a locale→value map for all supported locales (plus zh-CN primary).
 *
 * @example
 * extractI18nValues(row, 'product_name')
 * // → { 'zh-CN': '产品名称', 'en-US': 'Product Name', ... }
 */
export function extractI18nValues(
  record: Record<string, unknown> | null | undefined,
  fieldCode: string,
): Record<string, string> {
  if (!record) return {};

  const values: Record<string, string> = {};

  // Primary field → zh-CN
  const primaryValue = record[fieldCode];
  if (primaryValue != null) {
    values['zh-CN'] = String(primaryValue);
  }

  // Companion fields
  for (const locale of I18N_SUPPORTED_LOCALES) {
    const companionCode = getI18nFieldCode(fieldCode, locale);
    const companionValue = record[companionCode];
    if (companionValue != null) {
      values[locale] = String(companionValue);
    }
  }

  return values;
}
