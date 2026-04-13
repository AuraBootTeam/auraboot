/**
 * DndSubTableWrapper — shared DnD context for sub-table rows.
 *
 * Wraps children with @dnd-kit DndContext + SortableContext.
 * Provides drag handle, sort overlay, and drop handling.
 * Used by both SubTable (edit mode) and SubTableViewer (display mode).
 */

import React, { useState, useCallback } from 'react';
import {
  DndContext,
  DragOverlay,
  PointerSensor,
  KeyboardSensor,
  useSensor,
  useSensors,
  closestCenter,
  type DragStartEvent,
  type DragEndEvent,
} from '@dnd-kit/core';
import {
  SortableContext,
  verticalListSortingStrategy,
  sortableKeyboardCoordinates,
} from '@dnd-kit/sortable';
import type { TreeRow } from '~/framework/meta/hooks/useTreeData';

export interface DndSubTableWrapperProps {
  /** Row IDs for SortableContext (pid or id) */
  items: string[];
  /** Called when drag ends with oldIndex, newIndex, and horizontal offset */
  onDragEnd: (activeId: string, overId: string, deltaX: number) => void;
  /** Render the drag overlay (ghost row) */
  renderOverlay?: (activeRow: TreeRow | null) => React.ReactNode;
  children: React.ReactNode;
  disabled?: boolean;
}

export const DndSubTableWrapper: React.FC<DndSubTableWrapperProps> = ({
  items,
  onDragEnd,
  renderOverlay,
  children,
  disabled = false,
}) => {
  const [activeId, setActiveId] = useState<string | null>(null);
  const [initialX, setInitialX] = useState(0);

  const sensors = useSensors(
    useSensor(PointerSensor, {
      activationConstraint: { distance: 8 },
    }),
    useSensor(KeyboardSensor, {
      coordinateGetter: sortableKeyboardCoordinates,
    }),
  );

  const handleDragStart = useCallback((event: DragStartEvent) => {
    setActiveId(String(event.active.id));
    setInitialX(event.active.rect.current.translated?.left ?? 0);
  }, []);

  const handleDragEnd = useCallback(
    (event: DragEndEvent) => {
      const { active, over } = event;
      setActiveId(null);

      if (!over || active.id === over.id) return;

      // Calculate horizontal offset for indent sensing
      const currentX = active.rect.current.translated?.left ?? 0;
      const deltaX = currentX - initialX;

      onDragEnd(String(active.id), String(over.id), deltaX);
    },
    [onDragEnd, initialX],
  );

  if (disabled) {
    return <>{children}</>;
  }

  return (
    <DndContext
      sensors={sensors}
      collisionDetection={closestCenter}
      onDragStart={handleDragStart}
      onDragEnd={handleDragEnd}
    >
      <SortableContext items={items} strategy={verticalListSortingStrategy}>
        {children}
      </SortableContext>
      <DragOverlay>{activeId && renderOverlay ? renderOverlay(null) : null}</DragOverlay>
    </DndContext>
  );
};
