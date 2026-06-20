/**
 * useDesignerDnd — the reusable block-tree drag-and-drop kernel.
 *
 * Extracted verbatim (behavior-preserving) from UnifiedDesignerWorkbench so the
 * page designer and the report designer (block-tree family, B1 Phase 2) share
 * ONE @dnd-kit orchestration — the active-drag / drop-intent state plus the
 * drag start/over/end glue over the (already-tested) pure resolvers in
 * dndShared — instead of each reinventing it.
 *
 * The kernel stays executor-agnostic: it RESOLVES a drop action and hands it to
 * `onDropAction`; the host owns the switch that maps the action to its own
 * add/move executors. Drop capabilities, root-accept policy and the block-tree
 * accessors are injected, so the kernel never reaches into a specific store.
 */
import {
  KeyboardSensor,
  PointerSensor,
  useSensor,
  useSensors,
  type DragEndEvent,
  type DragOverEvent,
  type DragStartEvent,
} from '@dnd-kit/core';
import { useState } from 'react';
import type { ActiveDropIntent } from '../canvas/CanvasHost';
import {
  readDragData,
  readDropData,
  resolveBlockDropIntent,
  resolveCanvasBlockAncestorDropAction,
  resolveDragEndAction,
  type DragData,
  type DropCapabilities,
} from './dndShared';

/** A resolved, non-null drag-end action handed to the host for execution. */
export type DragEndAction = NonNullable<ReturnType<typeof resolveDragEndAction>>;

export interface UseDesignerDndParams {
  /** Per-target add/move permission checks (reference the registry + document). */
  dropCapabilities: DropCapabilities;
  /** Whether a palette block of this type may drop onto the page root. */
  canAddBlockToRoot: (blockType: string) => boolean;
  /** Ancestor id path (root-first) for a block — for canvas-block ancestor drops. */
  getBlockPath: (blockId: string) => string[];
  /** blockType for a block id (used by the ancestor-drop resolver). */
  getBlockType: (blockId: string) => string | undefined;
  /** Make a canvas block the primary selection when its drag starts. */
  onSelectCanvasBlock: (blockId: string) => void;
  /** Execute a resolved drop action against the host's own add/move handlers. */
  onDropAction: (action: DragEndAction) => void;
}

export interface DesignerDndController {
  activeDrag: DragData | null;
  activeDropIntent: ActiveDropIntent;
  rootAccepts: boolean;
  sensors: ReturnType<typeof useSensors>;
  handleDragStart: (event: DragStartEvent) => void;
  handleDragOver: (event: DragOverEvent) => void;
  handleDragEnd: (event: DragEndEvent) => void;
  /** Clear the active drag + intent (e.g. on DndContext onDragCancel). */
  clearActiveDrag: () => void;
}

export function useDesignerDnd({
  dropCapabilities,
  canAddBlockToRoot,
  getBlockPath,
  getBlockType,
  onSelectCanvasBlock,
  onDropAction,
}: UseDesignerDndParams): DesignerDndController {
  const [activeDrag, setActiveDrag] = useState<DragData | null>(null);
  const [activeDropIntent, setActiveDropIntent] = useState<ActiveDropIntent>(null);
  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 8 } }),
    useSensor(KeyboardSensor),
  );

  const rootAccepts =
    activeDrag?.kind === 'palette-block' && canAddBlockToRoot(activeDrag.blockType);

  const clearActiveDrag = () => {
    setActiveDrag(null);
    setActiveDropIntent(null);
  };

  const handleDragStart = (event: DragStartEvent) => {
    const drag = readDragData(event.active.data.current);
    setActiveDrag(drag);
    setActiveDropIntent(null);
    if (drag?.kind === 'canvas-block') onSelectCanvasBlock(drag.blockId);
  };

  const handleDragOver = (event: DragOverEvent) => {
    const drag = readDragData(event.active.data.current);
    const drop = readDropData(event.over?.data.current);
    if (!drag || !drop || drop.kind !== 'block') {
      setActiveDropIntent(null);
      return;
    }
    const intent = resolveBlockDropIntent(drag, drop.blockId, dropCapabilities);
    setActiveDropIntent(intent ? { blockId: drop.blockId, intent } : null);
  };

  const handleDragEnd = (event: DragEndEvent) => {
    const drag = readDragData(event.active.data.current);
    const drop = readDropData(event.over?.data.current);
    setActiveDrag(null);
    setActiveDropIntent(null);

    let action: ReturnType<typeof resolveDragEndAction> = null;
    if (drag?.kind === 'canvas-block' && drop?.kind === 'block') {
      const dropPath = getBlockPath(drop.blockId);
      action = resolveCanvasBlockAncestorDropAction(
        drag.blockId,
        dropPath,
        { ...dropCapabilities, canAddBlockToRoot },
        { getBlockType },
      );
    }
    action ??= resolveDragEndAction(drag, drop, { ...dropCapabilities, canAddBlockToRoot });
    if (!action) return;
    onDropAction(action);
  };

  return {
    activeDrag,
    activeDropIntent,
    rootAccepts,
    sensors,
    handleDragStart,
    handleDragOver,
    handleDragEnd,
    clearActiveDrag,
  };
}
