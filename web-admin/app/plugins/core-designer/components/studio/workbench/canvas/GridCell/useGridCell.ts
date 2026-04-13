import { useState } from 'react';
import { useDroppable, useDndMonitor } from '@dnd-kit/core';
import { DRAG_TYPES } from '~/plugins/core-designer/components/studio/workbench/constants';
import type { Component } from '~/plugins/core-designer/components/studio/domain/schema/types';
import type { GridCellProps } from '~/plugins/core-designer/components/studio/workbench/canvas/GridCell/types';

export const useGridCell = ({ row, column, component }: GridCellProps) => {
  const { setNodeRef, isOver } = useDroppable({
    id: `cell-${row}-${column}`,
    data: {
      type: DRAG_TYPES.GRID_CELL,
      position: { row, column },
    },
  });

  const [isDragTarget, setIsDragTarget] = useState(false);

  // 监听拖拽状态，当有组件被拖拽到已占用的单元格上时显示交换提示
  useDndMonitor({
    onDragStart() {
      setIsDragTarget(false);
    },
    onDragOver(event) {
      const { active, over } = event;

      // 检查是否是拖拽组件到当前单元格
      if (over?.id === `cell-${row}-${column}`) {
        const activeData = active.data.current;

        // 只有当拖拽的是现有组件且当前单元格已被占用时才显示交换提示
        if (activeData?.type === DRAG_TYPES.COMPONENT && component) {
          // 确保不是拖拽自己
          if (activeData.component?.id !== component.id) {
            setIsDragTarget(true);
          }
        } else {
          setIsDragTarget(false);
        }
      } else {
        setIsDragTarget(false);
      }
    },
    onDragEnd() {
      setIsDragTarget(false);
    },
  });

  return {
    setNodeRef,
    isOver,
    isDragTarget,
  };
};
