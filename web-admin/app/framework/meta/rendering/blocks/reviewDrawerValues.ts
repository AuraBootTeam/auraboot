import type { SchemaRuntime } from '~/framework/meta/runtime/schema-runtime';
import { getLocalizedText } from '~/routes/_shared/dynamic-route-utils';
import { readDataSourceRows, readPath } from './workbenchBlockUtils';

export type Tone = 'green' | 'amber' | 'red' | 'blue' | 'purple' | 'gray';

export type RawColumnItem = {
  index: number;
  header: string;
  normalizedHeader?: string;
  systemField?: string;
  value: unknown;
};

export type RawColumnGroup = {
  key: string;
  config: any;
  columns: RawColumnItem[];
  legacyTestId?: boolean;
};

export const toneClass: Record<Tone, string> = {
  green: 'bg-status-green-bg text-status-green border-status-green',
  amber: 'bg-status-amber-bg text-status-amber border-status-amber',
  red: 'bg-status-red-bg text-status-red border-status-red',
  blue: 'bg-status-blue-bg text-status-blue border-status-blue',
  purple: 'bg-status-blue-bg text-status-blue border-status-blue',
  gray: 'bg-status-gray-bg text-status-gray border-status-gray',
};

export const buttonClass: Record<string, string> = {
  primary: 'bg-accent text-white hover:opacity-90',
  secondary: 'border border-border bg-panel text-text hover:bg-hover',
  danger: 'bg-status-red text-white hover:opacity-90',
};

export function parseJsonValue(value: unknown): unknown {
  let current = value;
  for (let depth = 0; depth < 4; depth += 1) {
    if (
      current &&
      typeof current === 'object' &&
      !Array.isArray(current) &&
      ['json', 'jsonb'].includes(
        String((current as Record<string, unknown>).type || '').toLowerCase(),
      ) &&
      Object.prototype.hasOwnProperty.call(current, 'value')
    ) {
      current = (current as Record<string, unknown>).value;
      continue;
    }
    if (typeof current !== 'string') return current;
    const trimmed = current.trim();
    if (!trimmed || (!trimmed.startsWith('{') && !trimmed.startsWith('['))) return current;
    try {
      current = JSON.parse(trimmed);
    } catch {
      return current;
    }
  }
  return current;
}

export function formatValue(value: unknown, emptyText = '-'): string {
  if (value === undefined || value === null || value === '') return emptyText;
  if (Array.isArray(value) && value.length === 0) return emptyText;
  if (Array.isArray(value) && value.every((item) => typeof item !== 'object' || item === null)) {
    return value.map((item) => formatValue(item, emptyText)).join(', ');
  }
  if (typeof value === 'object') return JSON.stringify(value, null, 2);
  return String(value);
}

export function applyValueMap(
  value: unknown,
  config: any,
  locale: string,
  t: (key: string) => string,
): unknown {
  const valueMap = config?.valueMap;
  if (!valueMap || typeof valueMap !== 'object') return value;
  const key = String(value ?? '');
  if (!Object.prototype.hasOwnProperty.call(valueMap, key)) return value;
  return getLocalizedText(valueMap[key], locale, t);
}

export function formatConfiguredValue(
  value: unknown,
  config: any,
  locale: string,
  t: (key: string) => string,
): string {
  const mappedValue = applyValueMap(value, config, locale, t);
  if (config?.format === 'percent' && !isEmptyValue(mappedValue)) {
    const numeric = typeof mappedValue === 'number' ? mappedValue : Number(mappedValue);
    if (Number.isFinite(numeric)) {
      const percent = Math.abs(numeric) <= 1 ? numeric * 100 : numeric;
      return `${Number(percent.toFixed(2)).toString()}%`;
    }
  }
  return formatValue(mappedValue, config?.emptyText);
}

export function isEmptyValue(value: unknown): boolean {
  return value === undefined || value === null || value === '';
}

export function readFirstPathValue(source: unknown, paths: string[]): unknown {
  for (const path of paths) {
    const value = readPath(source, path);
    if (!isEmptyValue(value)) return value;
  }
  return undefined;
}

export function resolveRawColumnItems(record: any, config: any): RawColumnItem[] {
  if (!config || config.enabled === false) return [];
  const source = config.sourceField ? readPath(record, config.sourceField) : record;
  const parsedSource = parseJsonValue(source);
  const path = config.path || '__raw_columns';
  const rawColumns = path ? readPath(parsedSource, path) : parsedSource;
  if (!Array.isArray(rawColumns)) return [];

  const headerField = String(config.headerField || 'header');
  const normalizedHeaderField = String(config.normalizedHeaderField || 'normalizedHeader');
  const systemFieldField = String(config.systemFieldField || 'systemField');
  const valueField = String(config.valueField || 'value');

  return rawColumns.map((column, index) => {
    if (!column || typeof column !== 'object' || Array.isArray(column)) {
      return { index, header: `#${index + 1}`, value: column };
    }
    const header = readFirstPathValue(column, [headerField, 'name', 'key']);
    const normalizedHeader = readFirstPathValue(column, [normalizedHeaderField, 'normalized']);
    const systemField = readFirstPathValue(column, [systemFieldField, 'field']);
    return {
      index,
      header: formatValue(header, `#${index + 1}`),
      normalizedHeader: isEmptyValue(normalizedHeader) ? undefined : formatValue(normalizedHeader),
      systemField: isEmptyValue(systemField) ? undefined : formatValue(systemField),
      value: readPath(column, valueField),
    };
  });
}

export function resolveRawColumnGroups(record: any, compareConfig: any): RawColumnGroup[] {
  const groups: RawColumnGroup[] = [];
  const primaryColumns = resolveRawColumnItems(record, compareConfig?.rawColumns);
  if (primaryColumns.length > 0) {
    groups.push({
      key: 'source_columns',
      config: compareConfig.rawColumns,
      columns: primaryColumns,
      legacyTestId: true,
    });
  }
  const groupConfigs = Array.isArray(compareConfig?.rawColumnGroups)
    ? compareConfig.rawColumnGroups
    : [];
  groupConfigs.forEach((groupConfig: any, index: number) => {
    const columns = resolveRawColumnItems(record, groupConfig);
    if (columns.length === 0) return;
    groups.push({
      key: String(groupConfig.key || groupConfig.id || `group_${index}`),
      config: groupConfig,
      columns,
    });
  });
  return groups;
}

/** One rung of a supplier price ladder, as projected by the named query. */
export interface LadderRung {
  qty: string | number;
  price: string | number;
  current?: boolean;
  rangeLabel?: string;
}

/**
 * Parses the ladder array off a field value. Depending on the NamedQuery driver it can arrive as
 * an array, a JSON string, or the platform's typed JSONB envelope.
 */
export function parseLadderRungs(value: unknown): LadderRung[] | null {
  const raw = parseJsonValue(value);
  if (!Array.isArray(raw) || raw.length === 0) return null;
  const rungs = raw.filter(
    (item): item is LadderRung =>
      !!item && typeof item === 'object' && 'qty' in item && 'price' in item,
  );
  return rungs.length > 0 ? rungs : null;
}

/**
 * An http(s) URL safe to put in an href, or null. Evidence snapshots carry supplier detail links
 * copied verbatim from an upstream API, so the scheme is checked rather than assumed — javascript:
 * and data: never reach the DOM.
 */
export function safeExternalUrl(value: unknown): string | null {
  if (typeof value !== 'string') return null;
  const trimmed = value.trim();
  if (!trimmed) return null;
  try {
    const url = new URL(trimmed);
    return url.protocol === 'http:' || url.protocol === 'https:' ? url.toString() : null;
  } catch {
    return null;
  }
}

export function readFieldValue(record: any, config: any, fallbackRecord?: any): unknown {
  if (Object.prototype.hasOwnProperty.call(config, 'value')) return config.value;
  const source = config.sourceField ? readPath(record, config.sourceField) : record;
  const parsedSource = parseJsonValue(source);
  let value = readPath(parsedSource, config.field || config.valueField);
  if (isEmptyValue(value) && Array.isArray(config.fallbackFields)) {
    for (const fallbackField of config.fallbackFields) {
      value = readPath(parsedSource, fallbackField);
      if (!isEmptyValue(value)) break;
    }
  }
  if (!isEmptyValue(value) || !config.fallbackField || !fallbackRecord) return value;
  const fallbackSource = config.fallbackSourceField
    ? readPath(fallbackRecord, config.fallbackSourceField)
    : fallbackRecord;
  return readPath(parseJsonValue(fallbackSource), config.fallbackField);
}

// Reference/lookup fields carry a resolved `<field>_display` sibling on the record (the backend's
// generic GAP-124 reference enrichment, applied on both list and getById reads). When a detail field
// has no explicit valueMap and is read directly (no sourceField), prefer that display name so the
// drawer shows names instead of raw pids/ULIDs. Purely additive: falls back to the configured value
// when no `_display` sibling exists, so non-reference fields are unchanged.
export function resolveDisplayValue(
  record: any,
  config: any,
  locale: string,
  t: (key: string) => string,
): string {
  const field = config?.field || config?.valueField;
  if (field && !config?.valueMap && !config?.sourceField && record && typeof record === 'object') {
    const display = record[`${field}_display`];
    if (display !== undefined && display !== null && display !== '') {
      return formatValue(display, config?.emptyText);
    }
  }
  return formatConfiguredValue(readFieldValue(record, config), config, locale, t);
}

export function findRelatedRecord(runtime: SchemaRuntime, config: any, selectedRecord: any): any {
  if (!config?.dataSource) return selectedRecord;
  const rows = readDataSourceRows(runtime, config.dataSource);
  if (rows.length === 0) return {};
  const recordField = config.recordField || config.selectedField;
  const matchField = config.matchField;
  if (!recordField || !matchField) return rows[0] || {};
  const expected = readPath(selectedRecord, recordField);
  if (expected === undefined || expected === null || String(expected) === '') return rows[0] || {};
  return rows.find((row: any) => String(readPath(row, matchField)) === String(expected)) || {};
}

// Collects the block's own field configs (summaryBadges + source.summary.items) keyed by their
// field/valueField path, so titleTemplate substitutions can resolve dict/valueMap labels instead of
// leaking raw enum codes. Purely additive: paths without a configured valueMap fall back to the raw
// value, matching the pre-existing behaviour.
export function buildTemplateFieldConfigs(block: any): Map<string, any> {
  const map = new Map<string, any>();
  const add = (field: unknown, config: any) => {
    if (typeof field === 'string' && field && !map.has(field)) map.set(field, config);
  };
  const badges = Array.isArray(block?.summaryBadges) ? block.summaryBadges : [];
  for (const badge of badges) add(badge?.valueField, badge);
  const items = Array.isArray(block?.source?.summary?.items) ? block.source.summary.items : [];
  for (const item of items) add(item?.field, item);
  return map;
}

export function fillTemplate(
  template: string,
  runtime: SchemaRuntime,
  record: any,
  fieldConfigs: Map<string, any>,
  locale: string,
  t: (key: string) => string,
  relatedRecords: Record<string, unknown> = {},
): string {
  return template.replace(/\$\{([^}]+)\}/g, (_match, expression: string) => {
    const path = String(expression).trim();
    if (path.startsWith('record.')) {
      const fieldPath = path.slice(7);
      const value = readPath(record, fieldPath);
      const config = fieldConfigs.get(fieldPath);
      return config ? formatConfiguredValue(value, config, locale, t) : formatValue(value, '');
    }
    return formatValue(readPath({ ...runtime.getContext(), ...relatedRecords }, path), '');
  });
}

export function stateBindingFromExpression(expression: unknown): string | undefined {
  if (typeof expression !== 'string') return undefined;
  const match = expression.trim().match(/^\$\{state\.([A-Za-z0-9_]+)\}$/);
  return match?.[1];
}

export function sectionLabel(
  config: any,
  locale: string,
  t: (key: string) => string,
  fallback: string,
) {
  return getLocalizedText(config?.label || config?.title || fallback, locale, t);
}

export function localized(locale: string, t: (key: string) => string, zh: string, en: string) {
  return getLocalizedText({ 'zh-CN': zh, en }, locale, t);
}

export function candidateKey(candidate: any, index: number): string {
  return String(candidate?.pid ?? candidate?.id ?? index);
}

export function scoreToneClass(color: unknown): string {
  switch (String(color ?? '').toLowerCase()) {
    case 'green':
      return 'bg-status-green-bg text-status-green';
    case 'yellow':
    case 'amber':
      return 'bg-status-amber-bg text-status-amber';
    case 'red':
      return 'bg-status-red-bg text-status-red';
    default:
      return 'bg-status-green-bg text-status-green';
  }
}

export function configuredTone(config: any, rawValue: unknown): Tone {
  const rawKey = String(rawValue ?? '');
  const mappedTone = config?.toneMap?.[rawKey];
  return (mappedTone || config?.tone || 'gray') as Tone;
}
