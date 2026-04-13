import { useDraggable } from '@dnd-kit/core';
import { useEffect } from 'react';
import { DRAG_TYPES } from '~/plugins/core-designer/components/studio/workbench/constants';
import type { Component } from '~/plugins/core-designer/components/studio/domain/schema/types';
import type { DraggableWrapperData } from '~/plugins/core-designer/components/studio/workbench/canvas/drag/DraggableWrapper/types';

export const useDraggableWrapper = (
  component: Component,
  onComponentClick?: (component: Component, event: React.MouseEvent) => void,
) => {
  const { attributes, listeners, setNodeRef, transform, isDragging } = useDraggable({
    id: `component-${component.id}`,
    data: {
      type: DRAG_TYPES.COMPONENT,
      component: component,
    } as DraggableWrapperData,
  });

  const style = transform
    ? {
        transform: `translate3d(${transform.x}px, ${transform.y}px, 0)`,
        zIndex: isDragging ? 1000 : 'auto',
      }
    : undefined;

  return {
    attributes,
    listeners,
    setNodeRef,
    style,
    isDragging,
    onComponentClick,
  };
};
