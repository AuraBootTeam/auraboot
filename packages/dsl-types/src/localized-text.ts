/**
 * i18n text. Either a literal string (for non-translated content) or an
 * object keyed by locale code. Always-available locales: `en`, `zh`.
 */
export type LocalizedText = string | { [locale: string]: string }
