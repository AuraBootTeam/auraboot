import type { CommandInputPresentation, LocalizedText } from '~/framework/meta/schemas/types';
import { getLocalizedText } from '~/framework/meta/runtime/expression/i18n-renderer';

export interface ResolvedPromptSummaryItem {
  field: string;
  label: string | Record<string, string>;
  value: string | Record<string, string>;
  dictCode?: string;
}
export interface ResolvedPromptPresentation {
  waitForResult?: boolean;
  width?: number;
  showClose?: boolean;
  closeOnBackdrop?: boolean;
  summary?: { columns: 1 | 2; items: ResolvedPromptSummaryItem[] };
}
function primitive(value: unknown): string {
  return (typeof value === 'string' && value !== '') ||
    typeof value === 'number' ||
    typeof value === 'boolean'
    ? String(value)
    : '—';
}
function readPath(context: Record<string, unknown>, path: string): unknown {
  if (
    !/^[\w$]+(?:\.[\w$]+)*$/.test(path) ||
    path.split('.').some((key) => ['__proto__', 'prototype', 'constructor'].includes(key))
  )
    return undefined;
  return path
    .split('.')
    .reduce<unknown>(
      (value, key) =>
        value && typeof value === 'object' ? (value as Record<string, unknown>)[key] : undefined,
      context,
    );
}
export function snapshotPromptPresentation(
  title: string | Record<string, string> | undefined,
  presentation: CommandInputPresentation | undefined,
  context: Record<string, unknown>,
  feedback: { locale?: string; t?: (key: string) => string } = {},
): { title?: string | Record<string, string>; presentation?: ResolvedPromptPresentation } {
  const localize = (value: string | LocalizedText) => {
    const defined =
      typeof value === 'string'
        ? value
        : Object.fromEntries(
            Object.entries(value).filter(
              (entry): entry is [string, string] => typeof entry[1] === 'string',
            ),
          );
    return feedback.locale === undefined && typeof defined === 'object'
      ? defined
      : getLocalizedText(defined, feedback.locale, feedback.t);
  };
  const template = (value: string) =>
    value.replace(/\$\{([^}]+)\}/g, (_match, path: string) =>
      primitive(readPath(context, path.trim())),
    );
  const localizedTitle = title == null ? undefined : localize(title);
  const resolvedTitle =
    localizedTitle === undefined
      ? undefined
      : typeof localizedTitle === 'string'
        ? template(localizedTitle)
        : Object.fromEntries(
            Object.entries(localizedTitle).map(([locale, value]) => [locale, template(value)]),
          );
  if (presentation === undefined) return { title: resolvedTitle };
  if (
    !presentation ||
    typeof presentation !== 'object' ||
    Array.isArray(presentation) ||
    (presentation.width !== undefined &&
      (typeof presentation.width !== 'number' ||
        !Number.isFinite(presentation.width) ||
        presentation.width <= 0)) ||
    (presentation.showClose !== undefined && typeof presentation.showClose !== 'boolean') ||
    (presentation.closeOnBackdrop !== undefined &&
      typeof presentation.closeOnBackdrop !== 'boolean') ||
    (presentation.waitForResult !== undefined && typeof presentation.waitForResult !== 'boolean')
  )
    throw new Error('Invalid command input presentation');
  const summary = presentation.summary;
  if (
    summary !== undefined &&
    (!summary ||
      !Array.isArray(summary.fields) ||
      (summary.columns !== undefined && summary.columns !== 1 && summary.columns !== 2))
  )
    throw new Error('Invalid command input summary');
  const record =
    context.record && typeof context.record === 'object' && !Array.isArray(context.record)
      ? (context.record as Record<string, unknown>)
      : {};
  return {
    title: resolvedTitle,
    presentation: {
      ...(presentation.waitForResult === undefined
        ? {}
        : { waitForResult: presentation.waitForResult }),
      ...(presentation.width === undefined ? {} : { width: presentation.width }),
      ...(presentation.showClose === undefined ? {} : { showClose: presentation.showClose }),
      ...(presentation.closeOnBackdrop === undefined
        ? {}
        : { closeOnBackdrop: presentation.closeOnBackdrop }),
      ...(summary
        ? {
            summary: {
              columns: summary.columns ?? 2,
              items: summary.fields.map((field) => {
                if (
                  !field ||
                  typeof field.field !== 'string' ||
                  !field.field.trim() ||
                  ['__proto__', 'prototype', 'constructor'].includes(field.field) ||
                  (field.dictCode !== undefined &&
                    (typeof field.dictCode !== 'string' || !field.dictCode.trim()))
                )
                  throw new Error('Invalid command input summary field');
                return {
                  field: field.field,
                  label: field.label ? localize(field.label) : field.field,
                  value: primitive(record[field.field]),
                  ...(field.dictCode ? { dictCode: field.dictCode } : {}),
                };
              }),
            },
          }
        : {}),
    },
  };
}
