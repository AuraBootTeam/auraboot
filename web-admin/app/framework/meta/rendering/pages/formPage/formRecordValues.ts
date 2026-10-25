import type { FieldMetaInfo } from './formPageTypes';
import { isDatetimeDataType, normalizeDateTimeFormValue } from './formDateTime';

export function mergeLoadedRecordWithDirtyFields(
  loadedRecord: Record<string, any>,
  currentData: Record<string, any>,
  dirtyFields: ReadonlySet<string>,
): Record<string, any> {
  if (dirtyFields.size === 0) return loadedRecord;
  const merged = { ...loadedRecord };
  for (const fieldCode of dirtyFields) {
    if (Object.prototype.hasOwnProperty.call(currentData, fieldCode)) {
      merged[fieldCode] = currentData[fieldCode];
    }
  }
  return merged;
}

export function snakeToCamel(value: string): string {
  return value.replace(/_([a-zA-Z0-9])/g, (_, char: string) => char.toUpperCase());
}

export function camelToSnake(value: string): string {
  return value.replace(/[A-Z]/g, (char) => `_${char.toLowerCase()}`);
}

export function getFormFieldValueWithAlias(
  formData: Record<string, any>,
  fieldCode: string,
): unknown {
  if (Object.prototype.hasOwnProperty.call(formData, fieldCode)) {
    return formData[fieldCode];
  }
  const alias = fieldCode.includes('_') ? snakeToCamel(fieldCode) : camelToSnake(fieldCode);
  if (alias !== fieldCode && Object.prototype.hasOwnProperty.call(formData, alias)) {
    return formData[alias];
  }
  return undefined;
}

export function collectFormFieldDataTypes(
  blocks: any[] | undefined,
  modelFields: Record<string, Pick<FieldMetaInfo, 'dataType'>> = {},
): Record<string, string> {
  const types: Record<string, string> = {};
  const formBlocks = Array.isArray(blocks) ? blocks : [];
  for (const block of formBlocks) {
    if (block?.blockType !== 'form-section' || !Array.isArray(block.fields)) continue;
    for (const rawField of block.fields) {
      if (!rawField?.field) continue;
      const fieldCode = String(rawField.field);
      types[fieldCode] = rawField.dataType ? String(rawField.dataType) : (types[fieldCode] ?? '');
    }
  }
  for (const [fieldCode, meta] of Object.entries(modelFields)) {
    if (meta?.dataType) {
      types[fieldCode] = meta.dataType;
    }
  }
  return types;
}

export function isJsonLikeDataType(dataType?: string): boolean {
  return ['json', 'jsonb'].includes(String(dataType || '').toLowerCase());
}

export function tryParseJsonText(text: string): unknown {
  const trimmed = text.trim();
  if (!trimmed) return text;
  try {
    return JSON.parse(trimmed);
  } catch {
    return text;
  }
}

export function getJsonFormValueError(
  rawValue: any,
  dataType?: string,
  label = 'JSON',
): string | null {
  if (!isJsonLikeDataType(dataType)) return null;
  const value = unwrapJsonLikeValue(rawValue);
  if (value === undefined || value === null || value === '') return null;
  if (typeof value !== 'string') return null;
  const trimmed = value.trim();
  if (!trimmed) return null;
  const looksLikeObjectOrArray =
    (trimmed.startsWith('{') && trimmed.endsWith('}')) ||
    (trimmed.startsWith('[') && trimmed.endsWith(']'));
  if (!looksLikeObjectOrArray) {
    return `${label} must be a valid JSON object or array`;
  }
  try {
    const parsed = JSON.parse(trimmed);
    if (!parsed || typeof parsed !== 'object') {
      return `${label} must be a valid JSON object or array`;
    }
  } catch {
    return `${label} must be valid JSON`;
  }
  return null;
}

export function isJsonEnvelope(value: unknown): value is { type?: unknown; value: unknown } {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return false;
  const record = value as Record<string, unknown>;
  const type = String(record.type || '').toLowerCase();
  return (
    (type === 'json' || type === 'jsonb') && Object.prototype.hasOwnProperty.call(record, 'value')
  );
}

export function unwrapJsonLikeValue(rawValue: any): any {
  let current = rawValue;
  for (let depth = 0; depth < 8; depth += 1) {
    if (isJsonEnvelope(current)) {
      const envelopeValue = current.value;
      current = typeof envelopeValue === 'string' ? tryParseJsonText(envelopeValue) : envelopeValue;
      continue;
    }

    if (typeof current === 'string') {
      const parsed = tryParseJsonText(current);
      if (parsed !== current && isJsonEnvelope(parsed)) {
        current = parsed;
        continue;
      }
    }

    break;
  }
  return current;
}

export function normalizeLoadedFormValue(rawValue: any, dataType?: string): any {
  const normalized = unwrapJsonLikeValue(rawValue);
  if (isDatetimeDataType(dataType)) {
    return normalizeDateTimeFormValue(normalized);
  }
  const shouldFormatJson = isJsonLikeDataType(dataType) || normalized !== rawValue;
  if (!shouldFormatJson || normalized == null || normalized === '') {
    return normalized;
  }

  if (typeof normalized === 'string') {
    const parsed = tryParseJsonText(normalized);
    return parsed === normalized ? normalized : JSON.stringify(parsed, null, 2);
  }

  try {
    return JSON.stringify(normalized, null, 2);
  } catch {
    return String(normalized);
  }
}

export function normalizeLoadedRecordForForm(
  loadedRecord: Record<string, any>,
  fieldDataTypes: Record<string, string> = {},
): Record<string, any> {
  const normalized = Object.fromEntries(
    Object.entries(loadedRecord).map(([key, value]) => [
      key,
      normalizeLoadedFormValue(value, fieldDataTypes[key]),
    ]),
  ) as Record<string, any>;

  for (const [fieldCode, dataType] of Object.entries(fieldDataTypes)) {
    if (Object.prototype.hasOwnProperty.call(normalized, fieldCode)) {
      continue;
    }
    const alias = fieldCode.includes('_') ? snakeToCamel(fieldCode) : camelToSnake(fieldCode);
    if (alias !== fieldCode && Object.prototype.hasOwnProperty.call(loadedRecord, alias)) {
      normalized[fieldCode] = normalizeLoadedFormValue(loadedRecord[alias], dataType);
    }
  }

  return normalized;
}

export function unwrapLoadedRecordResponse(raw: any): Record<string, any> {
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return {};
  if (
    Object.prototype.hasOwnProperty.call(raw, 'data') &&
    (Object.prototype.hasOwnProperty.call(raw, 'code') ||
      Object.prototype.hasOwnProperty.call(raw, 'message') ||
      Object.prototype.hasOwnProperty.call(raw, 'timestamp'))
  ) {
    return unwrapLoadedRecordResponse(raw.data);
  }
  if (Array.isArray(raw.records)) {
    return raw.records[0] && typeof raw.records[0] === 'object' ? raw.records[0] : {};
  }
  if (raw.data && typeof raw.data === 'object' && !Array.isArray(raw.data)) {
    return unwrapLoadedRecordResponse(raw.data);
  }
  return raw;
}

export function normalizeLoadedRecordResponseForForm(
  raw: any,
  fieldDataTypes: Record<string, string> = {},
): Record<string, any> {
  return normalizeLoadedRecordForForm(unwrapLoadedRecordResponse(raw), fieldDataTypes);
}
