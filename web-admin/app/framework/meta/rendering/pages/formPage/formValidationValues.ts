import { resolveCommandErrorMessage } from '~/framework/meta/utils/commandResponseErrors';
import type { FormValidationResult } from './formPageTypes';

export function resolveValidationToastMessage(
  validationResult: FormValidationResult,
): string | null {
  const firstSummaryError = validationResult.summaryErrors[0];
  if (firstSummaryError) return firstSummaryError;
  // Field errors are already rendered inline and focused below. Duplicating the first field
  // message in a global toast leaves stale feedback floating over a corrected or saved form.
  return null;
}

export function pushUniqueMessage(target: string[], message?: string) {
  const normalized = String(message || '').trim();
  if (!normalized || target.includes(normalized)) return;
  target.push(normalized);
}

export function parseValidationSummaryMessages(message?: string | null): string[] {
  if (!message) return [];
  return message
    .split(';')
    .map((item) => item.trim())
    .filter(Boolean)
    .filter((item, index, arr) => arr.indexOf(item) === index);
}

/** Localize handler validation reasons before presenting the form summary. */
export function resolveCommandValidationSummary(
  message: string,
  commandCode: string,
  translate: (key: string, params?: Record<string, string>) => string,
  locale: string,
): string[] {
  return parseValidationSummaryMessages(message).map((reason) =>
    resolveCommandErrorMessage({ context: { detail: reason } }, commandCode, translate, locale),
  );
}
