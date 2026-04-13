/**
 * useBlockDropZone Hook
 *
 * Enables blocks on the canvas to receive field drops.
 * Determines the appropriate drop target type based on block type.
 */

import { useDroppable } from '@dnd-kit/core';
import type { DslBlock, BlockType } from '~/studio/domain/dsl/types';

export interface UseBlockDropZoneOptions {
  block: DslBlock;
  disabled?: boolean;
}

export type DropTargetType = 'fields' | 'columns' | null;

/**
 * Determine the drop target type based on block type
 */
function getDropTargetType(blockType: BlockType): DropTargetType {
  switch (blockType) {
    case 'filters':
    case 'form-section':
    case 'detail-section':
      return 'fields';
    case 'table':
      return 'columns';
    default:
      return null;
  }
}

/**
 * Get descriptive label for the drop target
 */
function getDropLabel(targetType: DropTargetType): string {
  switch (targetType) {
    case 'fields':
      return '添加字段';
    case 'columns':
      return '添加列';
    default:
      return '';
  }
}

export function useBlockDropZone({ block, disabled }: UseBlockDropZoneOptions) {
  const dropTargetType = getDropTargetType(block.blockType);
  const canAcceptFields = !!dropTargetType;

  const { setNodeRef, isOver, active } = useDroppable({
    id: `block-drop:${block.id}:${dropTargetType}`,
    disabled: disabled || !canAcceptFields,
    data: {
      blockId: block.id,
      blockType: block.blockType,
      dropTargetType,
    },
  });

  // Check if the active drag is a field (palette item)
  const isFieldDrag = active?.data.current?.type === 'palette-item';
  const showDropIndicator = isOver && isFieldDrag && canAcceptFields;
  const dropLabel = getDropLabel(dropTargetType);

  return {
    setNodeRef,
    isOver,
    canAcceptFields,
    dropTargetType,
    showDropIndicator,
    dropLabel,
  };
}

export default useBlockDropZone;
