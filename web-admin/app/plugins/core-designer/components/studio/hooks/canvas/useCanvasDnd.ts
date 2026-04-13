/**
 * useCanvasDnd — Drag-and-drop event handlers for the canvas editor
 *
 * Handles four drag scenarios:
 * 1. Palette block → canvas: adds a new block at the drop position
 * 2. Field → canvas/block: adds field to existing form-section or creates a new one
 * 3. Widget → canvas/form-section: adds widget field to form-section or creates a new one
 * 4. Block reorder: moves an existing block to a new position
 *
 * @since 4.0.0
 */

import { useCallback } from 'react';
import type { DragEndEvent } from '@dnd-kit/core';
import type { CanvasBlock } from '~/plugins/core-designer/components/studio/domain/canvas/types';
import { getDefaultColSpan } from '~/plugins/core-designer/components/studio/core/layout';
import {
  createFormSectionWithFieldLike,
  createWidgetFieldConfig,
  insertFieldLikeIntoFormSection,
  appendFieldLikeToFormSection,
} from '~/plugins/core-designer/components/studio/workbench/designers/canvas/canvasFormSectionAdd';

export type CanvasDragKind = 'palette' | 'field' | 'widget' | 'block' | null;

export interface UseCanvasDndOptions {
  blocks: CanvasBlock[];
  addBlock: (type: string, index?: number, config?: Record<string, unknown>) => CanvasBlock;
  addBlockAt: (type: string, col: number, colSpan: number, rowSpan?: number, config?: Record<string, unknown>) => CanvasBlock;
  moveBlock: (from: number, to: number) => void;
  updateBlock: (id: string, patch: Partial<CanvasBlock>) => void;
  reorderFields?: (blockId: string, fromIndex: number, toIndex: number) => void;
}

export interface UseCanvasDndReturn {
  handleDragEnd: (event: DragEndEvent) => void;
  handleRglDrop: (layout: any[], item: any | undefined, e: Event) => void;
  handleRglDropDragOver: (e: DragEvent) => { w?: number; h?: number } | false | void;
}

export function getCanvasDragKind(activeId: string | null | undefined): CanvasDragKind {
  if (!activeId) return null;
  if (activeId.startsWith('palette:')) return 'palette';
  if (activeId.startsWith('field:')) return 'field';
  if (activeId.startsWith('field-item:')) return 'field';
  if (activeId.startsWith('widget:')) return 'widget';
  return 'block';
}

export function blockAcceptsFieldLikeDrop(blockType: string): boolean {
  return blockType === 'form-section';
}

function shouldCreateSectionFromDrop(overId: string): boolean {
  return overId === 'canvas-drop-zone';
}

function parseFieldSlotDropTarget(overId: string): { blockId: string; index: number } | null {
  if (!overId.startsWith('field-slot:')) return null;
  const [, blockId, index] = overId.split(':');
  const parsed = Number(index);
  if (!blockId || Number.isNaN(parsed)) return null;
  return { blockId, index: parsed };
}

function parseFieldItemDropTarget(overId: string): { blockId: string; index: number } | null {
  if (!overId.startsWith('field-item:')) return null;
  const [, blockId, index] = overId.split(':');
  const parsed = Number(index);
  if (!blockId || Number.isNaN(parsed)) return null;
  return { blockId, index: parsed };
}

function findTargetFormSection(
  blocks: CanvasBlock[],
  overId: string,
  overSlotTarget: { blockId: string; index: number } | null,
): { block: CanvasBlock; insertIndex: number | null } | null {
  if (overSlotTarget) {
    const slotTargetBlock = blocks.find(
      (block) => block.id === overSlotTarget.blockId && block.blockType === 'form-section',
    );
    if (slotTargetBlock) {
      return { block: slotTargetBlock, insertIndex: overSlotTarget.index };
    }
  }

  const directTargetBlock = blocks.find(
    (block) => block.id === overId && block.blockType === 'form-section',
  );
  if (directTargetBlock) {
    return { block: directTargetBlock, insertIndex: null };
  }

  return null;
}
/**
 * Resolve the insertion index based on the drop target id
 */
function getDropIndex(overId: string, blocks: CanvasBlock[]): number {
  if (overId === 'canvas-drop-zone') return blocks.length;
  const idx = blocks.findIndex((b) => b.id === overId);
  return idx >= 0 ? idx : blocks.length;
}

export function useCanvasDnd(options: UseCanvasDndOptions): UseCanvasDndReturn {
  const { blocks, addBlock, addBlockAt, moveBlock, updateBlock, reorderFields } = options;

  const handleDragEnd = (event: DragEndEvent) => {
    const { active, over } = event;
    if (!over) return;

    const activeId = String(active.id);
    const overId = String(over.id);
    const overSlotTarget = parseFieldSlotDropTarget(overId);
    const overFieldItemTarget = parseFieldItemDropTarget(overId);

    // --- Scenario 0: existing form-section field reorder ---
    if (activeId.startsWith('field-item:')) {
      const activeField = parseFieldItemDropTarget(activeId);
      if (!activeField) return;

      if (overSlotTarget && overSlotTarget.blockId === activeField.blockId) {
        const rawTargetIndex = overSlotTarget.index;
        const adjustedIndex = activeField.index < rawTargetIndex ? rawTargetIndex - 1 : rawTargetIndex;
        if (adjustedIndex !== activeField.index) {
          reorderFields?.(activeField.blockId, activeField.index, adjustedIndex);
        }
        return;
      }

      if (overFieldItemTarget && overFieldItemTarget.blockId === activeField.blockId) {
        if (overFieldItemTarget.index !== activeField.index) {
          reorderFields?.(activeField.blockId, activeField.index, overFieldItemTarget.index);
        }
        return;
      }
    }

    // --- Scenario 1: palette block → canvas ---
    if (activeId.startsWith('palette:')) {
      const blockType = activeId.replace('palette:', '');
      const dropIndex = getDropIndex(overId, blocks);
      addBlock(blockType, dropIndex);
      return;
    }

    // --- Scenario 2: field → canvas / form-section block ---
    if (activeId.startsWith('field:')) {
      const fieldCode = activeId.replace('field:', '');

      const target = findTargetFormSection(blocks, overId, overSlotTarget);
      if (target) {
        const fields = (target.block.config.fields as string[] | undefined) ?? [];
        if (!fields.includes(fieldCode)) {
          if (target.insertIndex == null) {
            appendFieldLikeToFormSection(target.block, updateBlock, fieldCode);
          } else {
            insertFieldLikeIntoFormSection(target.block, updateBlock, fieldCode, target.insertIndex);
          }
        }
        return;
      }

      if (!shouldCreateSectionFromDrop(overId)) return;
      const dropIndex = getDropIndex(overId, blocks);
      createFormSectionWithFieldLike(addBlock, fieldCode, dropIndex);
      return;
    }

    // --- Scenario 3: widget → canvas / form-section block ---
    if (activeId.startsWith('widget:')) {
      const component = activeId.replace('widget:', '');
      const fieldConfig = createWidgetFieldConfig(component);

      const target = findTargetFormSection(blocks, overId, overSlotTarget);
      if (target) {
        if (target.insertIndex == null) {
          appendFieldLikeToFormSection(target.block, updateBlock, fieldConfig);
        } else {
          insertFieldLikeIntoFormSection(target.block, updateBlock, fieldConfig, target.insertIndex);
        }
        return;
      }

      if (!shouldCreateSectionFromDrop(overId)) return;
      const dropIndex = getDropIndex(overId, blocks);
      createFormSectionWithFieldLike(addBlock, fieldConfig, dropIndex);
      return;
    }

    // --- Scenario 4: block reorder ---
    const fromIndex = blocks.findIndex((b) => b.id === activeId);
    const toIndex = blocks.findIndex((b) => b.id === overId);
    if (fromIndex >= 0 && toIndex >= 0 && fromIndex !== toIndex) {
      moveBlock(fromIndex, toIndex);
    }
  };

  const handleRglDrop = useCallback(
    (_layout: any[], item: any | undefined, e: Event) => {
      if (!item) return;
      const dragEvent = e as unknown as DragEvent;
      const blockType = dragEvent.dataTransfer?.getData('text/x-block-type');
      if (!blockType) return;

      const colSpan = getDefaultColSpan(blockType);
      // Ensure block fits within 12 columns
      const col = Math.min(item.x ?? 0, 12 - colSpan);
      addBlockAt(blockType, Math.max(0, col), colSpan, item.h ?? 1);
    },
    [addBlockAt],
  );

  const handleRglDropDragOver = useCallback(
    (e: DragEvent) => {
      // Check if this is a block-type drag
      if (!e.dataTransfer?.types.includes('text/x-block-type')) return false;
      // Tell RGL the default size for the drop placeholder
      return { w: 6, h: 1 };
    },
    [],
  );

  return { handleDragEnd, handleRglDrop, handleRglDropDragOver };
}
