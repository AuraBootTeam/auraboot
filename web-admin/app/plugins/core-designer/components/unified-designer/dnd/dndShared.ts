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

/**
 * Order *containment* hits so the innermost droppable wins.
 *
 * `pointerWithin` reports EVERY droppable whose rect contains the pointer, so a
 * pointer over a nested field also reports the section and the page root. Its
 * own sort key (mean distance from the pointer to the rect corners) does not
 * reliably rank the innermost target first, so we rank by rect area ascending:
 * the smallest rect containing the pointer is the deepest target under it.
 *
 * Only ever apply this to droppables that genuinely contain the pointer — a
 * smallest-area rule over a mixed containment/proximity set lets a block the
 * pointer is nowhere near win (see `buildDesignerCollisionCandidates`).
 */
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

/**
 * Merge @dnd-kit's `pointerWithin` (containment) and `closestCenter` (proximity)
 * results into the designer's drop candidate list. `over` is `candidates[0]`.
 *
 * The two inputs answer different questions and must NOT be pooled:
 *  - `pointerWithin` = "which droppables is the pointer actually inside?" — the
 *    user's aim, and empty whenever the pointer sits in a gap between blocks.
 *  - `closestCenter` = "which droppable's center is nearest the dragged item's
 *    center?" — a proximity GUESS over every measured droppable that never
 *    checks containment, so it always returns something.
 *
 * Therefore: containment hits (innermost first) always outrank the proximity
 * fallback, which is kept only as a trailing last resort and is the sole answer
 * when the pointer is inside nothing at all.
 *
 * Ranking the proximity guess by area alongside the containment hits was the
 * cause of a silent no-op drop: for a container taller than the viewport
 * (measured: `form_root` at 1651px) the container's own center is hundreds of
 * pixels from the pointer, so `closestCenter` always contributed some small
 * descendant, which then won the area sort — a drop aimed at the container (even
 * on its own header) resolved to a descendant that rejects the block, and the
 * gesture did nothing at all.
 */
export function buildDesignerCollisionCandidates<T extends { id: CollisionIdentifier }>(
  pointerHits: T[],
  closestHits: T[],
  droppableRects: { get(id: CollisionIdentifier): CollisionRect | undefined },
): T[] {
  // Pointer outside every droppable (dragging over a gap): proximity is all we have.
  if (pointerHits.length === 0) return closestHits;

  const containmentCandidates = prioritizeNestedDropCollisions(pointerHits, droppableRects);
  const proximityFallback = closestHits[0];
  if (
    !proximityFallback ||
    containmentCandidates.some((candidate) => candidate.id === proximityFallback.id)
  ) {
    return containmentCandidates;
  }
  return [...containmentCandidates, proximityFallback];
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
