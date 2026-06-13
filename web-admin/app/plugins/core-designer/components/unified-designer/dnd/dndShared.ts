import type { ModelFieldDefinition } from '../types';

/**
 * @dnd-kit drag/drop payloads for the unified designer.
 *
 * Drag sources (useDraggable `data`):
 *  - palette-block: a block type dragged from the Blocks palette
 *  - model-field:   a model field dragged from the Fields library
 *  - canvas-block:  an existing block dragged on the canvas (reorder)
 *
 * Drop targets (useDroppable `data`):
 *  - block: an existing block frame (drop inside / before it)
 *  - root:  the page root drop zone
 */
export type DragData =
  | { kind: 'palette-block'; blockType: string }
  | { kind: 'model-field'; field: ModelFieldDefinition }
  | { kind: 'canvas-block'; blockId: string };

export type DropData = { kind: 'block'; blockId: string } | { kind: 'root' };

export const paletteDraggableId = (blockType: string) => `palette:${blockType}`;
export const fieldDraggableId = (field: ModelFieldDefinition) =>
  `model-field:${field.modelCode}.${field.code}`;
export const canvasDraggableId = (blockId: string) => `canvas:${blockId}`;
export const blockDroppableId = (blockId: string) => `drop-block:${blockId}`;
export const ROOT_DROPPABLE_ID = 'drop-root';

type CollisionIdentifier = string | number;
type CollisionRect = { width: number; height: number };

export function prioritizeNestedDropCollisions<T extends { id: CollisionIdentifier }>(
  collisions: T[],
  droppableRects: { get(id: CollisionIdentifier): CollisionRect | undefined },
): T[] {
  if (collisions.length < 2) return collisions;

  return collisions
    .map((collision, index) => ({ collision, index }))
    .sort((left, right) => {
      const areaDiff =
        getCollisionRectArea(droppableRects.get(left.collision.id)) -
        getCollisionRectArea(droppableRects.get(right.collision.id));
      return areaDiff === 0 ? left.index - right.index : areaDiff;
    })
    .map(({ collision }) => collision);
}

export function buildDesignerCollisionCandidates<T extends { id: CollisionIdentifier }>(
  pointerHits: T[],
  closestHits: T[],
  droppableRects: { get(id: CollisionIdentifier): CollisionRect | undefined },
): T[] {
  if (pointerHits.length === 0) return closestHits;

  const candidates = [...pointerHits];
  const closest = closestHits[0];
  if (closest && !candidates.some((candidate) => candidate.id === closest.id)) {
    candidates.push(closest);
  }
  return prioritizeNestedDropCollisions(candidates, droppableRects);
}

function getCollisionRectArea(rect: CollisionRect | undefined): number {
  if (!rect || !Number.isFinite(rect.width) || !Number.isFinite(rect.height)) {
    return Number.MAX_SAFE_INTEGER;
  }
  return Math.max(0, rect.width) * Math.max(0, rect.height);
}

export function readDragData(data: unknown): DragData | null {
  if (!data || typeof data !== 'object') return null;
  const candidate = data as Partial<DragData>;
  if (candidate.kind === 'palette-block' && typeof (candidate as { blockType?: unknown }).blockType === 'string') {
    return candidate as DragData;
  }
  if (candidate.kind === 'model-field' && (candidate as { field?: unknown }).field) {
    return candidate as DragData;
  }
  if (candidate.kind === 'canvas-block' && typeof (candidate as { blockId?: unknown }).blockId === 'string') {
    return candidate as DragData;
  }
  return null;
}

export function readDropData(data: unknown): DropData | null {
  if (!data || typeof data !== 'object') return null;
  const candidate = data as Partial<DropData>;
  if (candidate.kind === 'root') return { kind: 'root' };
  if (candidate.kind === 'block' && typeof (candidate as { blockId?: unknown }).blockId === 'string') {
    return candidate as DropData;
  }
  return null;
}

export type DropIntent = 'before' | 'inside';

/**
 * Capability probes provided by the workbench. The before/inside decision is
 * logic-driven (insert as sibling when allowed, otherwise nest), not geometric.
 */
export interface DropCapabilities {
  canAddBlockBeforeTarget(targetBlockId: string, blockType: string): boolean;
  canAddBlockToParent(parentBlockId: string, blockType: string): boolean;
  canAddModelFieldBeforeTarget(targetBlockId: string, field: ModelFieldDefinition): boolean;
  canAddModelFieldToParent(parentBlockId: string, field: ModelFieldDefinition): boolean;
  canMoveBlockBeforeTarget(movingBlockId: string, targetBlockId: string): boolean;
  canMoveBlockToParent(movingBlockId: string, parentBlockId: string): boolean;
}

/** Resolve the drop intent for a drag over an existing block, or null if not droppable. */
export function resolveBlockDropIntent(
  drag: DragData,
  targetBlockId: string,
  caps: DropCapabilities,
): DropIntent | null {
  if (drag.kind === 'model-field') {
    if (caps.canAddModelFieldBeforeTarget(targetBlockId, drag.field)) return 'before';
    if (caps.canAddModelFieldToParent(targetBlockId, drag.field)) return 'inside';
    return null;
  }
  if (drag.kind === 'palette-block') {
    if (caps.canAddBlockBeforeTarget(targetBlockId, drag.blockType)) return 'before';
    if (caps.canAddBlockToParent(targetBlockId, drag.blockType)) return 'inside';
    return null;
  }
  if (drag.blockId === targetBlockId) return null;
  if (caps.canMoveBlockBeforeTarget(drag.blockId, targetBlockId)) return 'before';
  if (caps.canMoveBlockToParent(drag.blockId, targetBlockId)) return 'inside';
  return null;
}

export type DragEndAction =
  | { type: 'add-block-before'; targetBlockId: string; blockType: string }
  | { type: 'add-block-inside'; parentBlockId: string; blockType: string }
  | { type: 'add-block-root'; blockType: string }
  | { type: 'add-field-before'; targetBlockId: string; field: ModelFieldDefinition }
  | { type: 'add-field-inside'; parentBlockId: string; field: ModelFieldDefinition }
  | { type: 'move-before'; movingBlockId: string; targetBlockId: string }
  | { type: 'move-inside'; movingBlockId: string; parentBlockId: string }
  | null;

export interface DragEndCapabilities extends DropCapabilities {
  canAddBlockToRoot(blockType: string): boolean;
}

/** Pure resolution of what a drag-end should do, given the drag/drop payloads. */
export function resolveDragEndAction(
  drag: DragData | null,
  drop: DropData | null,
  caps: DragEndCapabilities,
): DragEndAction {
  if (!drag || !drop) return null;

  if (drop.kind === 'root') {
    if (drag.kind === 'palette-block' && caps.canAddBlockToRoot(drag.blockType)) {
      return { type: 'add-block-root', blockType: drag.blockType };
    }
    return null;
  }

  const targetBlockId = drop.blockId;
  if (drag.kind === 'palette-block') {
    if (caps.canAddBlockBeforeTarget(targetBlockId, drag.blockType)) {
      return { type: 'add-block-before', targetBlockId, blockType: drag.blockType };
    }
    if (caps.canAddBlockToParent(targetBlockId, drag.blockType)) {
      return { type: 'add-block-inside', parentBlockId: targetBlockId, blockType: drag.blockType };
    }
    return null;
  }
  if (drag.kind === 'model-field') {
    if (caps.canAddModelFieldBeforeTarget(targetBlockId, drag.field)) {
      return { type: 'add-field-before', targetBlockId, field: drag.field };
    }
    if (caps.canAddModelFieldToParent(targetBlockId, drag.field)) {
      return { type: 'add-field-inside', parentBlockId: targetBlockId, field: drag.field };
    }
    return null;
  }
  if (drag.blockId !== targetBlockId && caps.canMoveBlockBeforeTarget(drag.blockId, targetBlockId)) {
    return { type: 'move-before', movingBlockId: drag.blockId, targetBlockId };
  }
  if (drag.blockId !== targetBlockId && caps.canMoveBlockToParent(drag.blockId, targetBlockId)) {
    return { type: 'move-inside', movingBlockId: drag.blockId, parentBlockId: targetBlockId };
  }
  return null;
}

export function resolveCanvasBlockAncestorDropAction(
  movingBlockId: string,
  targetBlockPathIds: string[],
  caps: DragEndCapabilities,
  options: { getBlockType?: (blockId: string) => string | undefined } = {},
): DragEndAction {
  const movingBlockType = options.getBlockType?.(movingBlockId);
  if (movingBlockType) {
    for (let index = targetBlockPathIds.length - 1; index >= 0; index -= 1) {
      const targetBlockId = targetBlockPathIds[index];
      if (options.getBlockType?.(targetBlockId) !== movingBlockType) continue;
      if (caps.canMoveBlockBeforeTarget?.(movingBlockId, targetBlockId)) {
        return { type: 'move-before', movingBlockId, targetBlockId };
      }
    }
  }

  for (let index = targetBlockPathIds.length - 1; index >= 0; index -= 1) {
    const targetBlockId = targetBlockPathIds[index];
    const action = resolveDragEndAction(
      { kind: 'canvas-block', blockId: movingBlockId },
      { kind: 'block', blockId: targetBlockId },
      caps,
    );
    if (action) return action;
  }
  return null;
}
