import type { CanvasBlock } from '~/studio/domain/canvas/types';
import { BlockRegistry, WidgetRegistry } from '~/studio/registry';
import { getCanvasBlockLabel } from './canvasBlockLabel';

function getFieldLikeLabel(field: unknown, index: number): string {
  if (typeof field === 'string') return field;
  const obj = field as Record<string, unknown>;
  if (typeof obj.label === 'string' && obj.label.trim()) return obj.label;
  if (typeof obj.component === 'string') return WidgetRegistry.getName(obj.component);
  if (typeof obj.field === 'string' && obj.field.trim()) return obj.field;
  return `Field ${index + 1}`;
}

export function resolveCanvasDragLabel(
  activeDragId: string | null,
  blocks: CanvasBlock[],
): string | null {
  if (!activeDragId) return null;

  if (activeDragId.startsWith('palette:')) {
    const blockType = activeDragId.replace('palette:', '');
    return BlockRegistry.get(blockType)?.name ?? blockType;
  }

  if (activeDragId.startsWith('widget:')) {
    return WidgetRegistry.getName(activeDragId.replace('widget:', ''));
  }

  if (activeDragId.startsWith('field:')) {
    return activeDragId.replace('field:', '');
  }

  if (activeDragId.startsWith('field-item:')) {
    const [, blockId, rawIndex] = activeDragId.split(':');
    const fieldIndex = Number(rawIndex);
    if (!blockId || Number.isNaN(fieldIndex)) return 'Field';
    const targetBlock = blocks.find((block) => block.id === blockId);
    if (!targetBlock || targetBlock.blockType !== 'form-section') return 'Field';
    const fields = (targetBlock.config.fields as unknown[] | undefined) ?? [];
    return getFieldLikeLabel(fields[fieldIndex], fieldIndex);
  }

  const targetBlock = blocks.find((block) => block.id === activeDragId);
  return targetBlock ? getCanvasBlockLabel(targetBlock) : null;
}
