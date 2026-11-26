import { tryParseJsonText, unwrapJsonLikeValue } from '~/framework/meta/rendering/pages/formPage/formRecordValues';
import type { DataSourceConfig } from '~/framework/smart/schema/smart-components';
import type { LocalizedText } from '~/framework/meta/schemas/types';

export interface StructuredArrayColumn {
  key: string;
  label: LocalizedText;
  required?: boolean;
  defaultValue?: string;
  options?: Array<{ label: LocalizedText; value: string }>;
  dataSource?: DataSourceConfig;
}

export interface StructuredArraySchema {
  columns?: StructuredArrayColumn[];
  item?: StructuredArrayColumn;
  addLabel?: LocalizedText;
}

export function readStructuredArray(value: unknown): unknown[] | null {
  const raw: unknown = unwrapJsonLikeValue(value);
  const parsed = typeof raw === 'string' ? tryParseJsonText(raw) : raw;
  if (parsed == null || parsed === '') return [];
  return Array.isArray(parsed) ? parsed : null;
}

export function invalidStructuredArrayRow(value: unknown, schema: StructuredArraySchema): number | null {
  const rows = readStructuredArray(value);
  if (!rows) return 0;
  const index = rows.findIndex((row) => {
    if (schema.item) return typeof row !== 'string' || row.trim() === '';
    if (!row || typeof row !== 'object' || Array.isArray(row)) return true;
    return (schema.columns ?? []).some((column) => {
      const cell = (row as Record<string, unknown>)[column.key];
      return column.required && (cell == null || String(cell).trim() === '');
    });
  });
  return index < 0 ? null : index;
}
