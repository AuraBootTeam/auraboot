import type { ColumnConfig } from '~/framework/meta/schemas/types';
import type { ColumnSettingsDefinition } from '~/framework/smart/components/view/ColumnSettingsPanel';

function snakeToCamel(value: string): string {
  return value.replace(/_([a-zA-Z0-9])/g, (_, char: string) => char.toUpperCase());
}

function camelToSnake(value: string): string {
  return value.replace(/[A-Z]/g, (char) => `_${char.toLowerCase()}`);
}

export function getListFieldValueWithAlias(
  record: Record<string, any>,
  fieldCode: string | undefined,
): unknown {
  // A bare-string column shorthand (e.g. `columns: ['name', 'pageKey']`) reaches the
  // legacy list runtime without a resolved `field`, so `column.field` can be undefined.
  // Guard the alias lookup here so such a column renders empty instead of throwing
  // `Cannot read properties of undefined (reading 'includes')`, which would blank the
  // whole page behind the render error boundary ("Oops!").
  if (!record || typeof fieldCode !== 'string' || fieldCode.length === 0) {
    return undefined;
  }
  if (Object.prototype.hasOwnProperty.call(record, fieldCode)) {
    return record[fieldCode];
  }
  const alias = fieldCode.includes('_') ? snakeToCamel(fieldCode) : camelToSnake(fieldCode);
  if (alias !== fieldCode && Object.prototype.hasOwnProperty.call(record, alias)) {
    return record[alias];
  }
  return undefined;
}

export function readRefTargetConfig(
  column: ColumnConfig,
  meta?: Record<string, any>,
): Record<string, any> {
  return {
    ...(meta?.extension?.refTarget || {}),
    ...(meta?.refTarget || {}),
    ...((column as any).extension?.refTarget || {}),
    ...((column as any).props?.refTarget || {}),
    ...((column as any).refTarget || {}),
  };
}

/**
 * Resolve a header/filter label from the model field metadata (field-meta API).
 * The importer denormalises the field's effective displayName (zh-CN first)
 * into the field extension, so dynamic pages can label columns even when the
 * page config carries no explicit label and no i18n resource exists. Returns
 * undefined when metadata is missing so callers keep their own fallbacks.
 */
export function resolveFieldMetaDisplayName(
  fieldCode: string,
  modelFieldMap: Map<string, any> | undefined,
): string | undefined {
  if (!fieldCode || !modelFieldMap) return undefined;
  const meta = modelFieldMap.get(fieldCode);
  if (!meta) return undefined;
  const candidate = meta.displayName ?? meta.extension?.displayName;
  if (typeof candidate !== 'string') return undefined;
  const trimmed = candidate.trim();
  return trimmed && trimmed !== fieldCode ? trimmed : undefined;
}

function normalizeFieldDataType(value: unknown): string | undefined {
  if (typeof value !== 'string') return undefined;
  const trimmed = value.trim();
  return trimmed ? trimmed.toLowerCase() : undefined;
}

export function resolveFieldMetaDataType(
  fieldCode: string,
  modelFieldMap: Map<string, any> | undefined,
): string | undefined {
  if (!fieldCode || !modelFieldMap) return undefined;
  const meta = modelFieldMap.get(fieldCode);
  if (!meta) return undefined;
  return (
    normalizeFieldDataType(meta.dataType) ??
    normalizeFieldDataType(meta.fieldType) ??
    normalizeFieldDataType(meta.extension?.dataType) ??
    normalizeFieldDataType(meta.extension?.fieldType)
  );
}

/**
 * A field's visual control lives in `extension.renderComponent` (colorpicker / progress /
 * rating / moneyinput / ...), NOT in its dataType. List cell-type resolution used to ignore
 * it, so those columns fell back to raw text/number. Surface it so the cell renderer can
 * pick the right presentation. Mirrors the designer's `platformTablePreview.inferColumnValueType`.
 */
export function resolveFieldMetaRenderComponent(
  fieldCode: string,
  modelFieldMap: Map<string, any> | undefined,
): string | undefined {
  if (!fieldCode || !modelFieldMap) return undefined;
  const meta = modelFieldMap.get(fieldCode);
  if (!meta) return undefined;
  const raw =
    meta.extension?.renderComponent ??
    meta.renderComponent ??
    meta.extension?.component ??
    meta.uiSchema?.component;
  return typeof raw === 'string' && raw.trim() ? raw.trim().toLowerCase() : undefined;
}

/** Map a renderComponent / DSL renderType to a list cell valueType (undefined = no override). */
export function renderComponentToValueType(
  component: string | undefined,
): ColumnConfig['valueType'] | undefined {
  switch (component) {
    case 'colorpicker':
    case 'color':
      return 'color';
    case 'progress':
    case 'progressfield':
      return 'progress';
    case 'rating':
    case 'ratingfield':
      return 'rating';
    case 'moneyinput':
    case 'money':
      return 'currency';
    default:
      return undefined;
  }
}

export function resolveColumnCapabilityDataType(
  column: { field?: string; dataType?: unknown; valueType?: unknown; sorter?: unknown },
  modelFieldMap: Map<string, any> | undefined,
): string {
  return (
    resolveFieldMetaDataType(column.field ?? '', modelFieldMap) ??
    normalizeFieldDataType(column.dataType) ??
    normalizeFieldDataType(column.valueType) ??
    normalizeFieldDataType(column.sorter) ??
    'text'
  );
}

export interface ListFilterFieldMetadata {
  fieldCode: string;
  label: string;
  fieldType: string;
  dictCode?: string;
  referenceModelCode?: string;
  referenceValueField?: string;
  referenceDisplayField?: string;
}

/**
 * Build the typed metadata shared by the filter picker, filter chips and value editor.
 *
 * A table column is primarily a presentation contract, so many DSL pages omit its
 * data type. The model field metadata remains the source of truth for filter
 * operators and editors (money must expose numeric comparisons, enums their dict,
 * and references their target model).
 */
export function buildListFilterFieldMetadata(
  tableColumns: ColumnConfig[],
  modelFieldMap: Map<string, any> | undefined,
  resolveLabel: (column: ColumnConfig) => string,
): ListFilterFieldMetadata[] {
  return tableColumns
    .filter((column) => !column.isActionColumn && Boolean(column.field))
    .map((column) => {
      const meta = modelFieldMap?.get(column.field);
      const refTarget = readRefTargetConfig(column, meta);
      const referenceModelCode = String(
        refTarget.modelCode ||
          refTarget.targetModel ||
          refTarget.targetEntity ||
          meta?.referenceModelCode ||
          meta?.extension?.referenceModelCode ||
          '',
      ).trim();
      return {
        fieldCode: column.field,
        label: resolveLabel(column),
        fieldType: resolveColumnCapabilityDataType(column, modelFieldMap),
        dictCode: column.dictCode || meta?.dictCode || meta?.extension?.dictCode || undefined,
        referenceModelCode: referenceModelCode || undefined,
        referenceValueField: String(
          refTarget.valueField || refTarget.targetValueField || refTarget.idField || 'pid',
        ),
        referenceDisplayField:
          String(
            refTarget.displayField || refTarget.labelField || refTarget.targetField || '',
          ).trim() || undefined,
      };
    });
}

export interface ViewManageFieldOption {
  code: string;
  name: string;
  dataType: string;
}

export function buildViewManageFieldOptions(
  tableColumns: ColumnConfig[],
  modelFieldMap: Map<string, any> | undefined,
): ViewManageFieldOption[] {
  const byCode = new Map<string, ViewManageFieldOption>();

  for (const [fieldCode] of modelFieldMap?.entries() ?? []) {
    if (!fieldCode) continue;
    byCode.set(fieldCode, {
      code: fieldCode,
      name: resolveFieldMetaDisplayName(fieldCode, modelFieldMap) ?? fieldCode,
      dataType: resolveFieldMetaDataType(fieldCode, modelFieldMap) ?? 'string',
    });
  }

  for (const column of tableColumns) {
    if (!column.field || column.isActionColumn) continue;
    const fallbackName =
      typeof column.label === 'string'
        ? column.label
        : (byCode.get(column.field)?.name ?? column.field);
    byCode.set(column.field, {
      code: column.field,
      name: fallbackName,
      dataType: resolveColumnCapabilityDataType(column, modelFieldMap),
    });
  }

  return Array.from(byCode.values());
}

export function buildListColumnSettingsDefinitions(
  baseColumns: ColumnConfig[],
  modelFieldMap: Map<string, any> | undefined,
  systemColumns: ColumnConfig[],
  resolveLabel: (column: ColumnConfig) => string,
): ColumnSettingsDefinition[] {
  const definitions = new Map<string, ColumnSettingsDefinition>();

  for (const column of baseColumns) {
    if (!column.field || column.isActionColumn) continue;
    definitions.set(column.field, {
      field: column.field,
      label: resolveLabel(column),
      ...(column.mandatory === true ? { mandatory: true } : {}),
      dataType: resolveColumnCapabilityDataType(column, modelFieldMap),
      group: 'business',
      defaultVisible: true,
      defaultWidth:
        typeof column.width === 'number'
          ? column.width
          : typeof column.width === 'string'
            ? Number.parseInt(column.width, 10) || undefined
            : undefined,
      defaultFrozenPosition: column.fixed,
    });
  }

  for (const [fieldCode, meta] of modelFieldMap?.entries() ?? []) {
    if (!fieldCode || meta?.visible === false || definitions.has(fieldCode)) continue;
    definitions.set(fieldCode, {
      field: fieldCode,
      label: resolveFieldMetaDisplayName(fieldCode, modelFieldMap) ?? fieldCode,
      dataType: resolveFieldMetaDataType(fieldCode, modelFieldMap) ?? 'text',
      group: 'business',
      defaultVisible: false,
    });
  }

  for (const column of systemColumns) {
    if (!column.field || definitions.has(column.field)) continue;
    definitions.set(column.field, {
      field: column.field,
      label: resolveLabel(column),
      dataType: resolveColumnCapabilityDataType(column, modelFieldMap),
      group: 'system',
      defaultVisible: false,
    });
  }

  return Array.from(definitions.values());
}

const SYSTEM_FIELD_I18N_KEYS: Record<string, string> = {
  created_at: 'common.created_at',
  updated_at: 'common.updated_at',
  created_by: 'common.creator',
  updated_by: 'common.modifier',
};

export function getSystemFieldI18nKey(fieldCode: string): string | undefined {
  return SYSTEM_FIELD_I18N_KEYS[fieldCode];
}

export function translateOrFallback(
  t: (key: string) => string,
  key: string,
  fallback: string,
): string {
  const resolved = t(key);
  return resolved && resolved !== key ? resolved : fallback;
}
