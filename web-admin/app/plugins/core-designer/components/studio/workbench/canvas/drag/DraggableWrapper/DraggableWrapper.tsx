import React, { useState, useRef, useCallback } from 'react';
import { useDraggableWrapper } from '~/plugins/core-designer/components/studio/workbench/canvas/drag/DraggableWrapper/useDraggableWrapper';
import type { DraggableWrapperProps } from '~/plugins/core-designer/components/studio/workbench/canvas/drag/DraggableWrapper/types';

/**
 * 可拖拽组件包装器
 * 为现有组件添加拖拽功能，支持在画布上移动和交换位置
 * 使用鼠标移动距离来区分点击和拖拽事件
 */
export const DraggableWrapper: React.FC<DraggableWrapperProps> = ({
  component,
  children,
  onComponentClick,
}) => {
  const [isDragging, setIsDragging] = useState(false);
  const [isMouseDown, setIsMouseDown] = useState(false);

  const mouseDownPosRef = useRef<{ x: number; y: number } | null>(null);
  const hasDragStartedRef = useRef(false);

  const DRAG_THRESHOLD = 5; // 5px移动距离阈值

  const {
    attributes,
    listeners,
    setNodeRef,
    style,
    isDragging: dndKitIsDragging,
  } = useDraggableWrapper(component);

  // 鼠标按下处理
  const handleMouseDown = useCallback((event: React.MouseEvent) => {
    // 只处理左键点击
    if (event.button !== 0) return;

    setIsMouseDown(true);
    hasDragStartedRef.current = false;
    mouseDownPosRef.current = { x: event.clientX, y: event.clientY };
  }, []);

  // 鼠标移动处理
  const handleMouseMove = useCallback(
    (event: React.MouseEvent) => {
      if (!isMouseDown || !mouseDownPosRef.current || hasDragStartedRef.current) return;

      const deltaX = Math.abs(event.clientX - mouseDownPosRef.current.x);
      const deltaY = Math.abs(event.clientY - mouseDownPosRef.current.y);
      const distance = Math.sqrt(deltaX * deltaX + deltaY * deltaY);

      // 如果移动距离超过阈值，开始拖拽
      if (distance > DRAG_THRESHOLD) {
        hasDragStartedRef.current = true;
        setIsDragging(true);
      }
    },
    [isMouseDown, component.id, DRAG_THRESHOLD],
  );

  // 鼠标抬起处理
  const handleMouseUp = useCallback(
    (event: React.MouseEvent) => {
      if (!isMouseDown) return;

      // 如果没有开始拖拽，认为是点击
      if (!hasDragStartedRef.current && mouseDownPosRef.current) {
        event.stopPropagation();
        onComponentClick?.(component, event);
      }

      // 重置状态
      setIsMouseDown(false);
      setIsDragging(false);
      mouseDownPosRef.current = null;
      hasDragStartedRef.current = false;
    },
    [isMouseDown, onComponentClick, component],
  );

  // 鼠标离开处理
  const handleMouseLeave = useCallback(() => {
    // 如果鼠标离开但还在按下状态，不重置状态，让拖拽继续
    // 只有在没有开始拖拽时才重置
    if (isMouseDown && !hasDragStartedRef.current) {
      setIsMouseDown(false);
      setIsDragging(false);
      mouseDownPosRef.current = null;
      hasDragStartedRef.current = false;
    }
  }, [isMouseDown]);

  return (
    <div
      ref={setNodeRef}
      style={style}
      {...attributes}
      {...listeners}
      onMouseDown={handleMouseDown}
      onMouseMove={handleMouseMove}
      onMouseUp={handleMouseUp}
      onMouseLeave={handleMouseLeave}
      className={` ${dndKitIsDragging ? 'opacity-50' : ''} ${isDragging ? 'cursor-grabbing' : 'cursor-pointer'} relative`}
      data-component-id={component.id}
      data-domain="canvas"
    >
      {/* 拖拽状态指示器 */}
      {isDragging && (
        <div className="pointer-events-none absolute inset-0 z-10">
          <div className="bg-opacity-50 absolute inset-0 rounded border-2 border-dashed border-blue-400 bg-blue-100"></div>
        </div>
      )}

      {children}
    </div>
  );
};
