/**
 * useDesignerDnd — the reusable block-tree drag-and-drop kernel.
 *
 * Pins the active-drag / drop-intent state machine and the start/over/end glue
 * the Workbench relied on, so the extraction is provably behavior-preserving and
 * the report designer (B1 Phase 2) can mount the same dnd orchestration. The
 * pure resolvers (resolveBlockDropIntent / resolveDragEndAction / …) have their
 * own tests in dndShared.test.ts; here we verify the hook's wiring.
 */
import { describe, expect, it, vi } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import type { DragEndEvent, DragOverEvent, DragStartEvent } from '@dnd-kit/core';
import { useDesignerDnd, type UseDesignerDndParams } from '../dnd/useDesignerDnd';
import type { DragData, DropData, DropCapabilities } from '../dnd/dndShared';

const allTrueCaps: DropCapabilities = {
  canAddBlockBeforeTarget: () => true,
  canAddBlockToParent: () => true,
  canAddModelFieldBeforeTarget: () => true,
  canAddModelFieldToParent: () => true,
  canMoveBlockBeforeTarget: () => true,
  canMoveBlockToParent: () => true,
};

function makeParams(overrides: Partial<UseDesignerDndParams> = {}): UseDesignerDndParams {
  return {
    dropCapabilities: allTrueCaps,
    canAddBlockToRoot: () => true,
    getBlockPath: () => [],
    getBlockType: () => undefined,
    onSelectCanvasBlock: vi.fn(),
    onDropAction: vi.fn(),
    ...overrides,
  };
}

const startEvent = (drag: DragData) =>
  ({ active: { data: { current: drag } } }) as unknown as DragStartEvent;
const overEvent = (drag: DragData, drop: DropData | null) =>
  ({
    active: { data: { current: drag } },
    over: drop ? { data: { current: drop } } : null,
  }) as unknown as DragOverEvent;
const endEvent = (drag: DragData, drop: DropData | null) =>
  ({
    active: { data: { current: drag } },
    over: drop ? { data: { current: drop } } : null,
  }) as unknown as DragEndEvent;

describe('useDesignerDnd', () => {
  it('drag start records the active drag; a palette block that may root-drop reports rootAccepts', () => {
    const { result } = renderHook(() => useDesignerDnd(makeParams()));
    act(() => result.current.handleDragStart(startEvent({ kind: 'palette-block', blockType: 'card' })));
    expect(result.current.activeDrag).toEqual({ kind: 'palette-block', blockType: 'card' });
    expect(result.current.rootAccepts).toBe(true);
  });

  it('rootAccepts is false when the root policy rejects the dragged palette block', () => {
    const { result } = renderHook(() =>
      useDesignerDnd(makeParams({ canAddBlockToRoot: () => false })),
    );
    act(() => result.current.handleDragStart(startEvent({ kind: 'palette-block', blockType: 'card' })));
    expect(result.current.rootAccepts).toBe(false);
  });

  it('drag start of a canvas block makes it the primary selection', () => {
    const onSelectCanvasBlock = vi.fn();
    const { result } = renderHook(() => useDesignerDnd(makeParams({ onSelectCanvasBlock })));
    act(() => result.current.handleDragStart(startEvent({ kind: 'canvas-block', blockId: 'b1' })));
    expect(onSelectCanvasBlock).toHaveBeenCalledWith('b1');
    expect(result.current.activeDrag).toEqual({ kind: 'canvas-block', blockId: 'b1' });
  });

  it('drag over a block target sets the drop intent', () => {
    const { result } = renderHook(() => useDesignerDnd(makeParams()));
    act(() =>
      result.current.handleDragOver(
        overEvent({ kind: 'palette-block', blockType: 'card' }, { kind: 'block', blockId: 't1' }),
      ),
    );
    expect(result.current.activeDropIntent).toEqual({ blockId: 't1', intent: expect.any(String) });
  });

  it('drag over with no droppable clears the drop intent', () => {
    const { result } = renderHook(() => useDesignerDnd(makeParams()));
    act(() =>
      result.current.handleDragOver(overEvent({ kind: 'palette-block', blockType: 'card' }, null)),
    );
    expect(result.current.activeDropIntent).toBeNull();
  });

  it('drag end resolves an action, dispatches it, and clears the active drag', () => {
    const onDropAction = vi.fn();
    const { result } = renderHook(() => useDesignerDnd(makeParams({ onDropAction })));
    act(() => result.current.handleDragStart(startEvent({ kind: 'palette-block', blockType: 'card' })));
    act(() =>
      result.current.handleDragEnd(
        endEvent({ kind: 'palette-block', blockType: 'card' }, { kind: 'block', blockId: 't1' }),
      ),
    );
    expect(onDropAction).toHaveBeenCalledTimes(1);
    expect(onDropAction.mock.calls[0][0]).toHaveProperty('type');
    expect(result.current.activeDrag).toBeNull();
    expect(result.current.activeDropIntent).toBeNull();
  });

  it('drag end with no droppable dispatches nothing but still clears state', () => {
    const onDropAction = vi.fn();
    const { result } = renderHook(() => useDesignerDnd(makeParams({ onDropAction })));
    act(() => result.current.handleDragStart(startEvent({ kind: 'palette-block', blockType: 'card' })));
    act(() =>
      result.current.handleDragEnd(endEvent({ kind: 'palette-block', blockType: 'card' }, null)),
    );
    expect(onDropAction).not.toHaveBeenCalled();
    expect(result.current.activeDrag).toBeNull();
  });

  it('clearActiveDrag resets active drag + intent', () => {
    const { result } = renderHook(() => useDesignerDnd(makeParams()));
    act(() => result.current.handleDragStart(startEvent({ kind: 'palette-block', blockType: 'card' })));
    act(() => result.current.clearActiveDrag());
    expect(result.current.activeDrag).toBeNull();
    expect(result.current.activeDropIntent).toBeNull();
  });
});
