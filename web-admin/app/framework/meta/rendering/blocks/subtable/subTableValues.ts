import type { ColumnConfig, ValidationRule } from '~/framework/meta/schemas/types';
import {
  formatInTimezone,
  resolveTemporalType,
  resolveTemporalFormat,
  type DateTimeFormatPreferences,
} from '~/shared/services/dateTimeFormatService';
import type {
  ChildFieldMeta,
  EnrichedColumnConfig,
  SubTableReferenceDisplayConfig,
} from './subTableTypes';

// ------- Utility Functions -------

export function formatCellValue(
  value: any,
  col: ColumnConfig | EnrichedColumnConfig,
  getDictLabel?: (code: string, value: string) => string | undefined,
  timeZone?: string,
  formats?: Partial<DateTimeFormatPreferences>,
  referenceDisplayValue?: string,
): string {
  if (value == null) return '-';

  if (isReferenceColumn(col as EnrichedColumnConfig)) {
    return referenceDisplayValue || '-';
  }

  // Dict label lookup
  if (col.dictCode && getDictLabel) {
    const label = getDictLabel(col.dictCode, String(value));
    if (label) return label;
  }

  // Temporal values: backend emits UTC, convert to the effective display
  // timezone via the canonical formatter (never slice the raw ISO string).
  const temporalType = resolveTemporalType(col.field, col.valueType as string | undefined, value);
  if (temporalType) {
    const fmt = resolveTemporalFormat(temporalType, formats, col.format);
    return formatInTimezone(value, fmt, timeZone);
  }

  if (col.format === 'currency' || col.valueType === 'currency') {
    return formatNumber(Number(value), col.precision);
  }

  if (col.format === 'percent') {
    return `${(Number(value) * 100).toFixed(1)}%`;
  }

  if (typeof value === 'boolean') {
    return value ? 'Yes' : 'No';
  }

  if (typeof value === 'number') {
    return formatNumber(value, col.precision);
  }

  return String(value);
}

export function getFieldMetaExtension(meta?: ChildFieldMeta): Record<string, any> {
  const extension = meta?.extension || {};
  const nested =
    extension.extension && typeof extension.extension === 'object'
      ? (extension.extension as Record<string, any>)
      : {};
  return { ...nested, ...extension };
}

export function resolveChildFieldDisplayName(meta?: ChildFieldMeta): string | undefined {
  if (!meta) return undefined;
  const extension = getFieldMetaExtension(meta);
  const candidate =
    meta.displayName ||
    extension.displayName ||
    (meta as any)['displayName:zh-CN'] ||
    (meta as any)['displayName:en'] ||
    (meta as any)['displayName:en-US'];
  if (typeof candidate !== 'string') return undefined;
  const trimmed = candidate.trim();
  return trimmed && trimmed !== meta.code ? trimmed : undefined;
}

export function shouldReplaceGeneratedLabel(
  label: ColumnConfig['label'],
  fieldCode: string,
): boolean {
  if (!label) return true;
  if (typeof label === 'string') {
    const trimmed = label.trim();
    return !trimmed || trimmed === fieldCode;
  }
  const values = Object.values(label).filter((value): value is string => typeof value === 'string');
  if (values.length === 0) return true;
  return values.every((value) => !value.trim() || value.trim() === fieldCode);
}

export function readSubTableRefTargetConfig(column: EnrichedColumnConfig): Record<string, any> {
  return {
    ...((column.extension as any)?.refTarget || {}),
    ...((column as any).props?.refTarget || {}),
    ...(column.refTarget || {}),
  };
}

export function collectSubTableReferenceDisplayConfigs(
  columns: EnrichedColumnConfig[],
): SubTableReferenceDisplayConfig[] {
  const configs: SubTableReferenceDisplayConfig[] = [];
  const seen = new Set<string>();

  for (const column of columns) {
    if (!column.field || column.isActionColumn) continue;
    const refTarget = readSubTableRefTargetConfig(column);
    const dataType = String(column.dataType || column.valueType || '').toLowerCase();
    const hasReferenceShape =
      dataType === 'reference' ||
      column.valueType === 'reference' ||
      Object.keys(refTarget).length > 0;
    if (!hasReferenceShape) continue;

    const modelCode = String(
      refTarget.modelCode ||
        refTarget.targetModel ||
        refTarget.targetEntity ||
        column.referenceModelCode ||
        '',
    ).trim();
    const displayField = String(
      refTarget.displayField || refTarget.labelField || refTarget.targetField || '',
    ).trim();
    const valueField = String(
      refTarget.valueField || refTarget.targetValueField || refTarget.idField || 'pid',
    ).trim();
    if (!modelCode || !displayField || !valueField) continue;

    const config: SubTableReferenceDisplayConfig = {
      field: column.field,
      modelCode,
      valueField,
      displayField,
      displayKey: `${column.field}_display`,
    };
    const key = buildSubTableReferenceDisplayCacheKey(config);
    if (seen.has(key)) continue;
    seen.add(key);
    configs.push(config);
  }

  return configs;
}

export function buildSubTableReferenceDisplayCacheKey(
  config: SubTableReferenceDisplayConfig,
): string {
  return `${config.field}|${config.modelCode}|${config.valueField}|${config.displayField}`;
}

export function isReferenceColumn(col: EnrichedColumnConfig): boolean {
  const dataType = String(col.dataType || col.valueType || '').toLowerCase();
  return (
    dataType === 'reference' ||
    col.valueType === 'reference' ||
    Object.keys(readSubTableRefTargetConfig(col)).length > 0 ||
    Boolean(col.referenceModelCode)
  );
}

export function formatNumber(num: number, precision = 2): string {
  const digits = precision ?? 2;
  if (!Number.isInteger(digits) || digits < 0 || digits > 20) {
    throw new Error('Sub-table numeric precision must be an integer from 0 to 20');
  }
  return num.toLocaleString('zh-CN', {
    minimumFractionDigits: 0,
    maximumFractionDigits: digits,
  });
}

export function isNumericField(col: ColumnConfig): boolean {
  return (
    col.format === 'currency' ||
    col.format === 'number' ||
    col.field.endsWith('_qty') ||
    col.field.endsWith('_price') ||
    col.field.endsWith('_amount') ||
    col.field.endsWith('_count')
  );
}

/** Evaluate a single ValidationRule against a value */
export function evaluateValidationRule(
  rule: ValidationRule,
  value: any,
  t: (key: string) => string,
): string | null {
  const getMessage = () => {
    if (typeof rule.message === 'string') {
      const resolved = t(rule.message);
      return resolved !== rule.message ? resolved : rule.message;
    }
    const msg = rule.message as any;
    return msg?.['zh-CN'] || msg?.['en-US'] || '';
  };

  switch (rule.type) {
    case 'required':
      if (value === undefined || value === null || value === '') return getMessage();
      break;
    case 'min':
      if (rule.min !== undefined && Number(value) < rule.min) return getMessage();
      break;
    case 'max':
      if (rule.max !== undefined && Number(value) > rule.max) return getMessage();
      break;
    case 'pattern':
      if (rule.pattern && typeof value === 'string' && !new RegExp(rule.pattern).test(value))
        return getMessage();
      break;
  }
  return null;
}
