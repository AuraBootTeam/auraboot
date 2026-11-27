/**
 * A detail section whose every field is empty. Shown as a field grid it is a row of labels over
 * dashes, which reads as a broken page; it is shown as one line that says so instead.
 */
import { getLocalizedText } from '~/routes/_shared/dynamic-route-utils';
import type { BlockConfig, FieldConfig } from '~/framework/meta/schemas/types';
import { readDetailRecordField } from './detailRecordRequests';
import type { RecordData } from './detailPageTypes';

/** Whether a value carries something to read. Absent, null, blank, `{}` and `[]` do not; 0 and false do. */
function hasContent(value: unknown): boolean {
  if (value === undefined || value === null) return false;
  if (typeof value === 'string') return value.trim() !== '';
  if (Array.isArray(value)) return value.length > 0;
  if (typeof value === 'object') return Object.keys(value as object).length > 0;
  return true;
}

/** A field shows nothing when both its value and the display name the backend resolved for it are empty. */
export function isDetailValueEmpty(value: unknown, displayValue?: unknown): boolean {
  return !hasContent(displayValue) && !hasContent(value);
}

/**
 * True when the section has at least one field and not one of them has a value.
 * A section without fields is not "empty" — there is nothing it could have shown.
 */
export function detailSectionIsEmpty(
  fields: FieldConfig[] | undefined,
  record: RecordData | undefined,
): boolean {
  if (!fields || fields.length === 0) return false;
  return fields.every((field) => {
    const value = record ? readDetailRecordField(record, field.field) : undefined;
    const displayValue = record ? readDetailRecordField(record, `${field.field}_display`) : undefined;
    return isDetailValueEmpty(value, displayValue);
  });
}

export const DETAIL_SECTION_EMPTY_KEY = 'detail.section.empty';

/**
 * What the empty line says: the page's own `emptyText` when it names one (it can say why —
 * "this run's plan has no migration"), else the platform's sentence.
 */
export function detailSectionEmptyText(block: BlockConfig, locale: string, t: (key: string) => string): string {
  const source = block as { emptyText?: unknown; extension?: { emptyText?: unknown } };
  const authored = source.emptyText ?? source.extension?.emptyText;
  if (typeof authored === 'string' && authored.trim()) return authored;
  if (authored && typeof authored === 'object') return getLocalizedText(authored as never, locale, t);
  const translated = t(DETAIL_SECTION_EMPTY_KEY);
  if (translated && translated !== DETAIL_SECTION_EMPTY_KEY) return translated;
  return locale === 'zh-CN' ? '这一段还没有内容。' : 'Nothing has been recorded in this section yet.';
}
