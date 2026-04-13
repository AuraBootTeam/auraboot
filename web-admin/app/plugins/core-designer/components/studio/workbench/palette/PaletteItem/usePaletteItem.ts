import { useState, useEffect } from 'react';
import { useDraggable } from '@dnd-kit/core';
import { DRAG_TYPES } from '~/plugins/core-designer/components/studio/workbench/constants';
import type { PaletteItemProps } from '~/plugins/core-designer/components/studio/workbench/palette/PaletteItem/types';

export const usePaletteItem = ({ type, name }: PaletteItemProps) => {
  const [isClient, setIsClient] = useState(false);

  // 确保只在客户端渲染时启用拖拽功能
  useEffect(() => {
    setIsClient(true);
  }, []);

  const { attributes, listeners, setNodeRef, transform, isDragging } = useDraggable({
    id: `palette-${type}`,
    data: {
      type: DRAG_TYPES.PALETTE_ITEM,
      component: {
        type,
        name,
        props: {
          label: name,
          placeholder: `请输入${name}`,
        },
        span: 1,
      },
    },
    // 只在客户端启用拖拽
    disabled: !isClient,
  });

  const style = transform
    ? {
        transform: `translate3d(${transform.x}px, ${transform.y}px, 0)`,
        zIndex: 1000,
      }
    : undefined;

  return {
    attributes: isClient ? attributes : {},
    listeners: isClient ? listeners : {},
    setNodeRef,
    style,
    isDragging,
    isClient,
  };
};
