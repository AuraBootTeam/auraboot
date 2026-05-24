import type { ModelFieldDefinition } from '../types';

/**
 * Coarse, type-derived field group keys for the designer field library.
 * Kept i18n-friendly (labels resolved from DESIGNER_I18N.unified.fieldGroup)
 * instead of reusing the studio's Chinese-hardcoded SEMANTIC_TYPE_INFO.
 */
export type FieldGroupKey =
  | 'relation'
  | 'choice'
  | 'datetime'
  | 'boolean'
  | 'number'
  | 'file'
  | 'json'
  | 'text'
  | 'other';

/** Stable display order for field groups. */
export const FIELD_GROUP_ORDER: FieldGroupKey[] = [
  'text',
  'number',
  'choice',
  'datetime',
  'boolean',
  'relation',
  'file',
  'json',
  'other',
];

function normalizeType(type: string | undefined): string {
  return (type ?? '').replace(/[\s_-]/g, '').toLowerCase();
}

export function fieldGroupKey(field: ModelFieldDefinition): FieldGroupKey {
  const refModel = field.refTarget?.modelCode;
  const type = normalizeType(field.type);

  if (refModel || ['relation', 'lookup', 'reference', 'ref', 'belongsto', 'hasone'].includes(type)) {
    return 'relation';
  }
  if (field.dictCode || ['enum', 'dict', 'dictionary', 'select'].includes(type)) return 'choice';
  if (['date', 'datetime', 'timestamp', 'time'].includes(type)) return 'datetime';
  if (['boolean', 'bool'].includes(type)) return 'boolean';
  if (
    ['integer', 'int', 'long', 'decimal', 'number', 'float', 'double', 'money', 'currency'].includes(
      type,
    )
  ) {
    return 'number';
  }
  if (['file', 'attachment', 'image'].includes(type)) return 'file';
  if (type === 'json') return 'json';
  if (['text', 'longtext', 'textarea', 'richtext', 'string', 'email', 'phone', 'url', ''].includes(type)) {
    return 'text';
  }
  return 'other';
}

export interface FieldGroup {
  key: FieldGroupKey;
  fields: ModelFieldDefinition[];
}

/** Group fields by derived key, preserving FIELD_GROUP_ORDER and skipping empty groups. */
export function groupModelFields(fields: ModelFieldDefinition[]): FieldGroup[] {
  const buckets = new Map<FieldGroupKey, ModelFieldDefinition[]>();
  for (const field of fields) {
    const key = fieldGroupKey(field);
    const bucket = buckets.get(key) ?? [];
    bucket.push(field);
    buckets.set(key, bucket);
  }
  return FIELD_GROUP_ORDER.filter((key) => (buckets.get(key)?.length ?? 0) > 0).map((key) => ({
    key,
    fields: buckets.get(key) ?? [],
  }));
}
