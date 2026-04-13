import { useCallback, useMemo, useState } from 'react';
import type { DragOverEvent, DragStartEvent } from '@dnd-kit/core';
import type { DragEndEvent } from '@dnd-kit/core';
import type { CanvasBlock } from '~/studio/domain/canvas/types';
import { getCanvasDragKind } from '~/studio/hooks/canvas/useCanvasDnd';
import { resolveCanvasDragLabel } from './canvasDragLabel';

interface UseCanvasDragStateOptions {
  blocks: CanvasBlock[];
  handleDragEnd: (event: DragEndEvent) => void;
}

export function useCanvasDragState(options: UseCanvasDragStateOptions) {
  const { blocks, handleDragEnd } = options;
  const [activeDragId, setActiveDragId] = useState<string | null>(null);
  const [activeOverId, setActiveOverId] = useState<string | null>(null);

  const onDragStart = useCallback((event: DragStartEvent) => {
    setActiveDragId(String(event.active.id));
  }, []);

  const onDragOver = useCallback((event: DragOverEvent) => {
    setActiveOverId(event.over ? String(event.over.id) : null);
  }, []);

  const resetDragState = useCallback(() => {
    setActiveDragId(null);
    setActiveOverId(null);
  }, []);

  const onDragEnd = useCallback((event: DragEndEvent) => {
    handleDragEnd(event);
    resetDragState();
  }, [handleDragEnd, resetDragState]);

  const activeDragKind = useMemo(
    () => getCanvasDragKind(activeDragId),
    [activeDragId],
  );
  const activeDragLabel = useMemo(
    () => resolveCanvasDragLabel(activeDragId, blocks),
    [activeDragId, blocks],
  );

  return {
    activeDragId,
    activeDragKind,
    activeDragLabel,
    activeOverId,
    onDragCancel: resetDragState,
    onDragEnd,
    onDragOver,
    onDragStart,
  };
}
