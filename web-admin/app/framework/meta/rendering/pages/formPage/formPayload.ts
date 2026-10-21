import { uploadCommandFile } from '~/framework/meta/utils/promptUpload';
import type { FieldMetaInfo, BomUploadReviewValueLike } from './formPageTypes';
import { isDatetimeDataType, normalizeDateTimePayloadValue } from './formDateTime';
import { isJsonLikeDataType, unwrapJsonLikeValue } from './formRecordValues';

export function normalizeCommandPayloadValue(rawValue: any, dataType?: string): any {
  if (Array.isArray(rawValue) && String(dataType || '').toLowerCase() === 'string') {
    const path = rawValue.filter((item) => typeof item === 'string' && item !== '');
    return path[path.length - 1] ?? '';
  }
  const normalized = normalizePayloadValue(rawValue, dataType);
  if (isJsonLikeDataType(dataType) && normalized != null && typeof normalized === 'object') {
    return JSON.stringify(normalized);
  }
  return normalized;
}

export function isEmptySubmittedValue(value: any): boolean {
  if (value === undefined || value === null) return true;
  if (Array.isArray(value) && value.length === 0) return true;
  return (
    value && typeof value === 'object' && !Array.isArray(value) && Object.keys(value).length === 0
  );
}

export function collectSubmitPayloadFieldTypes(blocks: any[] | undefined): Record<string, string> {
  const fieldTypes: Record<string, string> = {};

  const visit = (items: any[] | undefined) => {
    for (const block of items ?? []) {
      if (block?.blockType === 'form-section' && Array.isArray(block.fields)) {
        for (const rawField of block.fields) {
          const fieldCode = typeof rawField?.field === 'string' ? rawField.field : '';
          if (!fieldCode || rawField.submitPayload !== true) continue;
          fieldTypes[fieldCode] = String(rawField.dataType || 'string');
        }
      }
      visit(block?.blocks);
      visit(block?.tabs?.flatMap((tab: any) => tab?.blocks ?? []));
    }
  };

  visit(blocks);
  return fieldTypes;
}

/**
 * A create-form field rendered by BomUploadReview carries a BomUploadReviewValue
 * (local File + selected column mapping). Before the command payload is built,
 * upload the reviewed file and expand the value into the slot fields the create
 * handler expects (<field>, <field>_id, <field>_filename) plus the review
 * payload (bom_sheet_name / bom_header_row_index / bom_selected_columns).
 */
export async function expandBomUploadReviewPayload(
  actionRecord: Record<string, any>,
  modelFields: Record<string, Pick<FieldMetaInfo, 'dataType'>>,
  token?: string,
): Promise<{ expansions: Record<string, any>; reviewedFields: string[] }> {
  const expansions: Record<string, any> = {};
  const reviewedFields: string[] = [];
  for (const [key, raw] of Object.entries(actionRecord)) {
    const value = raw as BomUploadReviewValueLike | null | undefined;
    if (
      !value ||
      typeof value !== 'object' ||
      !(value.file instanceof File) ||
      !value.payload ||
      !Array.isArray(value.payload.bom_selected_columns)
    ) {
      continue;
    }
    const dataType = modelFields[key]?.dataType;
    // Some review slots persist upload metadata as JSON text rather than a file column.
    if (dataType && !['file', 'text'].includes(String(dataType).toLowerCase())) {
      continue;
    }
    const fileId = await uploadCommandFile(value.file, token);
    reviewedFields.push(key);
    expansions[key] = JSON.stringify([{ fileId, name: value.file.name }]);
    expansions[`${key}_id`] = fileId;
    expansions[`${key}_filename`] = value.file.name;
    expansions.bom_sheet_name = value.payload.bom_sheet_name;
    expansions.bom_header_row_index = value.payload.bom_header_row_index;
    expansions.bom_selected_columns = value.payload.bom_selected_columns;
  }
  return { expansions, reviewedFields };
}

export function getBomUploadReviewError(value: unknown, fallback: string): string | null {
  if (!value || typeof value !== 'object') return null;
  const review = value as BomUploadReviewValueLike;
  if (!(review.file instanceof File) || !review.payload) return null;
  const columns = review.payload.bom_selected_columns;
  if (
    review.valid !== true ||
    !Array.isArray(columns) ||
    columns.filter((column) => column.role === 'quantity').length !== 1
  ) {
    return review.validationMessage || fallback;
  }
  return null;
}

export function buildFormCommandPayload(
  actionRecord: Record<string, any>,
  modelFields: Record<string, Pick<FieldMetaInfo, 'dataType'>>,
  blocks?: any[],
  explicitlyClearedFields: ReadonlySet<string> = new Set(),
): Record<string, any> {
  const submitPayloadFieldTypes = collectSubmitPayloadFieldTypes(blocks);
  const modelFieldEntries = Object.entries(modelFields);

  if (modelFieldEntries.length > 0) {
    return Object.fromEntries(
      Object.entries(actionRecord).flatMap(([key, rawValue]) => {
        const dataType = modelFields[key]?.dataType || submitPayloadFieldTypes[key];
        if (!dataType) return [];
        const value = normalizeCommandPayloadValue(rawValue, dataType);
        if (isEmptySubmittedValue(value) && !(value === null && explicitlyClearedFields.has(key))) {
          return [];
        }
        return [[key, value]];
      }),
    );
  }

  return Object.fromEntries(
    Object.entries(actionRecord).flatMap(([key, rawValue]) => {
      if (
        key === 'id' ||
        key === 'pid' ||
        key === 'tenant_id' ||
        key === 'created_at' ||
        key === 'created_by' ||
        key === 'updated_at' ||
        key === 'updated_by' ||
        key === 'deleted_flag' ||
        key === 'deleted_at' ||
        key === 'deleted_by' ||
        key.startsWith('_')
      ) {
        return [];
      }
      const dataType = submitPayloadFieldTypes[key];
      const value = dataType ? normalizeCommandPayloadValue(rawValue, dataType) : rawValue;
      if (isEmptySubmittedValue(value) && !(value === null && explicitlyClearedFields.has(key))) {
        return [];
      }
      return [[key, value]];
    }),
  );
}

/** Pure CRUD writes business changes, including reviewed AI drafts; audit data stays display-only. */
export function buildDynamicCrudFormPayload(
  record: Record<string, any>,
  modelFields: Record<string, Pick<FieldMetaInfo, 'dataType'>>,
  dirtyFields: ReadonlySet<string>,
  operation: 'create' | 'update',
  initialRecord?: Record<string, any> | null,
): Record<string, any> {
  const systemFields = new Set([
    'id',
    'pid',
    'tenant_id',
    'created_at',
    'created_by',
    'updated_at',
    'updated_by',
    'deleted_flag',
    'deleted_at',
    'deleted_by',
    'row_version',
  ]);
  const hasFieldMetadata = Object.keys(modelFields).length > 0;
  return Object.fromEntries(
    Object.entries(record).flatMap(([key, rawValue]) => {
      if (systemFields.has(key) || key.startsWith('_') || (hasFieldMetadata && !modelFields[key]))
        return [];
      const dataType = modelFields[key]?.dataType;
      const value = normalizePayloadValue(rawValue, dataType);
      // Persistence tracks changed business values independently of manual-edit
      // protection. AI apply/undo uses setFormData and must not acquire a manual lock.
      const hasInitialRecord = initialRecord != null;
      const changed = hasInitialRecord
        ? JSON.stringify(value) !==
          JSON.stringify(normalizePayloadValue(initialRecord[key], dataType))
        : dirtyFields.has(key);
      if (operation === 'update' && !changed) return [];
      const explicitNull =
        value === null &&
        (dirtyFields.has(key) || (operation === 'update' && hasInitialRecord && changed));
      if (isEmptySubmittedValue(value) && !explicitNull) return [];
      return [[key, value]];
    }),
  );
}

/** Decode the persisted JSON collection before registering public file relations. */
export function collectAttachmentRelationIds(value: unknown): string[] {
  const decoded = typeof value === 'string' ? JSON.parse(value) : value;
  if (!Array.isArray(decoded)) throw new Error('Attachment collection must be an array.');
  return decoded.map((item) => {
    if (!item || typeof item !== 'object' || typeof item.fileId !== 'string' || !item.fileId) {
      throw new Error('Attachment collection requires public file identifiers.');
    }
    return item.fileId;
  });
}

export function normalizePayloadValue(rawValue: any, dataType?: string) {
  if (isDatetimeDataType(dataType)) {
    return normalizeDateTimePayloadValue(rawValue);
  }
  if (String(dataType || '').toLowerCase() === 'file') {
    if (rawValue == null || rawValue === '') {
      return null;
    }
    if (typeof rawValue === 'string') {
      return rawValue;
    }
    if (Array.isArray(rawValue)) {
      const normalizedFiles = rawValue
        .filter((item) => item && typeof item === 'object')
        .filter((item) => !('status' in item) || item.status === 'done')
        .map((item) => {
          const response = item.response && typeof item.response === 'object' ? item.response : {};
          const url =
            item.url ||
            item.thumbUrl ||
            (response as any).url ||
            (response as any).downloadUrl ||
            ((response as any).fileId || (response as any).pid
              ? `/api/file/download/${(response as any).fileId || (response as any).pid}`
              : undefined);
          return {
            name: item.name,
            url,
            size: item.size,
            type: item.type,
            fileId: (response as any).fileId || (response as any).pid,
          };
        })
        .filter((item) => item.name && item.url);

      return normalizedFiles.length > 0 ? JSON.stringify(normalizedFiles) : null;
    }
  }
  if (
    rawValue === '' &&
    ['date', 'datetime', 'decimal', 'integer'].includes(String(dataType || '').toLowerCase())
  ) {
    return null;
  }
  if (isJsonLikeDataType(dataType)) {
    const unwrapped = unwrapJsonLikeValue(rawValue);
    if (typeof unwrapped !== 'string') {
      return unwrapped;
    }
    const trimmed = unwrapped.trim();
    if (
      (trimmed.startsWith('[') && trimmed.endsWith(']')) ||
      (trimmed.startsWith('{') && trimmed.endsWith('}'))
    ) {
      try {
        return JSON.parse(trimmed);
      } catch {
        // Keep original string when not valid JSON text
      }
    }
    return unwrapped;
  }
  return rawValue;
}
