import type { ModelFieldDefinition } from '../types';

export const PALETTE_BLOCK_TYPE_MIME = 'application/x-auraboot-block-type';
export const MODEL_FIELD_MIME = 'application/x-auraboot-model-field';

export function readPaletteBlockType(dataTransfer: DataTransfer): string {
  return dataTransfer.getData(PALETTE_BLOCK_TYPE_MIME);
}

export function writeModelFieldPayload(
  dataTransfer: DataTransfer,
  field: ModelFieldDefinition,
) {
  dataTransfer.setData(MODEL_FIELD_MIME, JSON.stringify(field));
  dataTransfer.setData('text/plain', `model-field:${field.modelCode}.${field.code}`);
}

export function readModelFieldPayload(dataTransfer: DataTransfer): ModelFieldDefinition | null {
  const raw = dataTransfer.getData(MODEL_FIELD_MIME);
  if (!raw) return null;

  try {
    const parsed = JSON.parse(raw) as ModelFieldDefinition;
    if (!parsed.modelCode || !parsed.code) return null;
    return parsed;
  } catch {
    return null;
  }
}
