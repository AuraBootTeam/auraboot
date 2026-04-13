import type { CanvasBlock } from '~/studio/domain/canvas/types';

let widgetFieldCounter = 0;

export function createWidgetFieldConfig(component: string): { field: string; component: string } {
  widgetFieldCounter += 1;
  return {
    field: `widget_${Date.now()}_${widgetFieldCounter}`,
    component,
  };
}

export function appendFieldLikeToFormSection(
  block: CanvasBlock,
  updateBlock: (id: string, patch: Partial<CanvasBlock>) => void,
  fieldLike: unknown,
): number {
  const existingFields = (block.config.fields as unknown[] | undefined) ?? [];
  updateBlock(block.id, {
    config: { ...block.config, fields: [...existingFields, fieldLike] },
  });
  return existingFields.length;
}

export function insertFieldLikeIntoFormSection(
  block: CanvasBlock,
  updateBlock: (id: string, patch: Partial<CanvasBlock>) => void,
  fieldLike: unknown,
  insertIndex: number,
): void {
  const existingFields = (block.config.fields as unknown[] | undefined) ?? [];
  const nextFields = [...existingFields];
  const safeIndex = Math.max(0, Math.min(insertIndex, nextFields.length));
  nextFields.splice(safeIndex, 0, fieldLike);
  updateBlock(block.id, {
    config: { ...block.config, fields: nextFields },
  });
}

export function createFormSectionWithFieldLike(
  addBlock: (type: string, index?: number, config?: Record<string, unknown>) => CanvasBlock,
  fieldLike: unknown,
  index?: number,
): CanvasBlock {
  return addBlock('form-section', index, { fields: [fieldLike], columns: 3 });
}

export function resolveAdjacentSectionInsertIndex(
  blocks: CanvasBlock[],
  selectedBlockId: string | null,
): number | undefined {
  if (!selectedBlockId) return undefined;
  const selectedIndex = blocks.findIndex((block) => block.id === selectedBlockId);
  return selectedIndex >= 0 ? selectedIndex + 1 : undefined;
}
