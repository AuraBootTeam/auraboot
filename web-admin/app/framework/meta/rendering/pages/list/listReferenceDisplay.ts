import type { ColumnConfig } from '~/framework/meta/schemas/types';
import type { ViewFilterConfig } from '~/framework/smart/types/savedView';
import { readRefTargetConfig } from './listFieldMetadata';

export interface ListReferenceDisplayConfig {
  field: string;
  modelCode: string;
  valueField: string;
  displayField: string;
  displayKey: string;
}

export function buildListReferenceDisplayCacheKey(config: ListReferenceDisplayConfig): string {
  return `${config.field}|${config.modelCode}|${config.valueField}|${config.displayField}`;
}

interface ListSystemReferenceDisplayConfig {
  detailEndpoint: string;
  labelFields: string[];
}

const LIST_SYSTEM_REFERENCE_DISPLAY_MODELS: Record<string, ListSystemReferenceDisplayConfig> = {
  sys_user: {
    detailEndpoint: '/api/admin/users',
    labelFields: ['displayName', 'realName', 'username', 'email'],
  },
};

export function resolveListSystemReferenceDisplayConfig(
  modelCode: string,
): ListSystemReferenceDisplayConfig | undefined {
  return LIST_SYSTEM_REFERENCE_DISPLAY_MODELS[modelCode];
}

/**
 * System-reference detail endpoints accept public identifiers, not projected
 * display labels. Some list APIs already return a label in the raw reference
 * field; whitespace cannot occur in a path-style public PID, so such values
 * should render directly instead of generating guaranteed 404 lookups.
 */
export function shouldResolveListSystemReferenceValue(value: string): boolean {
  const normalized = value.trim();
  return normalized.length > 0 && !/\s/u.test(normalized);
}

export function pickSystemReferenceLabel(
  row: Record<string, any>,
  configuredDisplayField: string,
  labelFields: string[],
): string | undefined {
  for (const field of [configuredDisplayField, ...labelFields]) {
    const value = row[field];
    if (value === null || value === undefined) continue;
    const text = String(value).trim();
    if (text) return text;
  }
  return undefined;
}

export function collectListReferenceDisplayConfigs(
  columns: ColumnConfig[],
  modelFieldMap: Map<string, any>,
): ListReferenceDisplayConfig[] {
  const configs: ListReferenceDisplayConfig[] = [];
  const seen = new Set<string>();

  for (const column of columns) {
    if (!column.field || column.isActionColumn) continue;
    const meta = modelFieldMap.get(column.field);
    const refTarget = readRefTargetConfig(column, meta);
    const dataType = String(
      (column as any).dataType ||
        column.valueType ||
        meta?.dataType ||
        meta?.extension?.dataType ||
        '',
    ).toLowerCase();
    const hasReferenceShape =
      dataType === 'reference' ||
      column.valueType === 'reference' ||
      column.field.endsWith('_id') ||
      Object.keys(refTarget).length > 0;
    if (!hasReferenceShape) continue;

    const modelCode = String(
      refTarget.modelCode ||
        refTarget.targetModel ||
        refTarget.targetEntity ||
        meta?.referenceModelCode ||
        meta?.extension?.referenceModelCode ||
        '',
    ).trim();
    const displayField = String(
      refTarget.displayField || refTarget.labelField || refTarget.targetField || '',
    ).trim();
    const valueField = String(
      refTarget.valueField || refTarget.targetValueField || refTarget.idField || 'pid',
    ).trim();
    if (!modelCode || !displayField || !valueField) continue;

    const config: ListReferenceDisplayConfig = {
      field: column.field,
      modelCode,
      valueField,
      displayField,
      displayKey: `${column.field}_display`,
    };
    const key = buildListReferenceDisplayCacheKey(config);
    if (seen.has(key)) continue;
    seen.add(key);
    configs.push(config);
  }

  return configs;
}

export function collectListReferenceValues(
  config: ListReferenceDisplayConfig,
  records: Record<string, any>[],
  filters: ViewFilterConfig[],
  cached: Record<string, string>,
): string[] {
  const rowValues = records
    .filter((record) => !record[config.displayKey])
    .map((record) => record[config.field]);
  const filterValues = filters
    .filter((filter) => filter.fieldCode === config.field && !filter.isExpression)
    .flatMap((filter) => (Array.isArray(filter.value) ? filter.value : [filter.value]));
  return Array.from(
    new Set(
      [...rowValues, ...filterValues]
        .filter((value) => value !== null && value !== undefined && value !== '')
        .map(String),
    ),
  ).filter((value) => cached[value] === undefined);
}

export function resolveListReferenceFilterLabel(
  filter: ViewFilterConfig,
  configs: ListReferenceDisplayConfig[],
  cache: Record<string, Record<string, string>>,
  fallback: string,
): string | undefined {
  const config = configs.find((candidate) => candidate.field === filter.fieldCode);
  if (!config || filter.operator === 'isNull' || filter.operator === 'isNotNull') return undefined;
  const labels = cache[buildListReferenceDisplayCacheKey(config)] || {};
  const values = Array.isArray(filter.value) ? filter.value : [filter.value];
  return values
    .filter((value) => value !== null && value !== undefined && value !== '')
    .map((value) => labels[String(value)] || fallback)
    .join('、');
}
