/**
 * i18n text. Either a literal string (for non-translated content) or an
 * object keyed by locale code. Always-available locales: `en`, `zh`.
 */
/**
 * i18n text. Either a literal string (for non-translated content) or an
 * object keyed by locale code. Always-available locales: `en`, `zh`.
 *
 * Index access is `string | undefined`: reading a locale key that the record
 * does not carry genuinely yields undefined at runtime, and the type now says
 * so (0.0.2 soundness fix — previously the index signature claimed `string`,
 * which let `.trim()` chains type-check on absent keys).
 */
export type LocalizedText = string | { [locale: string]: string | undefined }
