import React, { useCallback, useRef, useEffect, useMemo } from 'react';
import { Copy, Trash2, Move, RotateCcw, Settings } from 'lucide-react';
import type { Component } from '~/studio/domain/schema/types';

export interface SelectionOverlayProps {
  selectedComponents: Array<
    Component & { position: { x: number; y: number }; size?: { width?: number; height?: number } }
  >;
  onCopy: () => void;
  onDelete: () => void;
  onDuplicate: () => void;
  onMove: (dx: number, dy: number) => void;
  onResize: (componentId: string, width: number, height: number) => void;
  onOpenProperties: (componentId: string) => void;
  className?: string;
}

type ResizeHandle = 'nw' | 'n' | 'ne' | 'e' | 'se' | 's' | 'sw' | 'w';

export function SelectionOverlay({
  selectedComponents,
  onCopy,
  onDelete,
  onDuplicate,
  onMove,
  onResize,
  onOpenProperties,
  className = '',
}: SelectionOverlayProps) {
  const overlayRef = useRef<HTMLDivElement>(null);
  const isDragging = useRef(false);
  const isResizing = useRef(false);
  const resizeHandle = useRef<ResizeHandle | null>(null);
  const dragStart = useRef({ x: 0, y: 0 });
  const initialBounds = useRef({ x: 0, y: 0, width: 0, height: 0 });

  const selectionBounds = useMemo(() => {
    if (selectedComponents.length === 0) return null;
    let minX = Infinity;
    let minY = Infinity;
    let maxX = -Infinity;
    let maxY = -Infinity;

    selectedComponents.forEach((component) => {
      const { x, y } = component.position;
      const { width = 100, height = 100 } = component.size || {};

      minX = Math.min(minX, x);
      minY = Math.min(minY, y);
      maxX = Math.max(maxX, x + width);
      maxY = Math.max(maxY, y + height);
    });

    return {
      x: minX,
      y: minY,
      width: maxX - minX,
      height: maxY - minY,
    };
  }, [selectedComponents]);

  const handleMouseDown = useCallback(
    (event: React.MouseEvent) => {
      if (!selectionBounds) return;
      isDragging.current = true;
      dragStart.current = { x: event.clientX, y: event.clientY };
      initialBounds.current = { ...selectionBounds };
      event.preventDefault();
      event.stopPropagation();
    },
    [selectionBounds],
  );

  const handleMouseMove = useCallback(
    (event: MouseEvent) => {
      if (!selectionBounds) return;
      if (isDragging.current) {
        const deltaX = event.clientX - dragStart.current.x;
        const deltaY = event.clientY - dragStart.current.y;
        onMove(deltaX, deltaY);
      } else if (isResizing.current && resizeHandle.current) {
        const deltaX = event.clientX - dragStart.current.x;
        const deltaY = event.clientY - dragStart.current.y;
        selectedComponents.forEach((component) => {
          const { width = 100, height = 100 } = component.size || {};
          let newWidth = width;
          let newHeight = height;

          switch (resizeHandle.current) {
            case 'e':
            case 'ne':
            case 'se':
              newWidth = Math.max(50, width + deltaX);
              break;
            case 's':
            case 'se':
            case 'sw':
              newHeight = Math.max(50, height + deltaY);
              break;
          }

          onResize(component.id, newWidth, newHeight);
        });
      }
    },
    [onMove, onResize, selectedComponents, selectionBounds],
  );

  const handleMouseUp = useCallback(() => {
    isDragging.current = false;
    isResizing.current = false;
    resizeHandle.current = null;
  }, []);

  useEffect(() => {
    document.addEventListener('mousemove', handleMouseMove);
    document.addEventListener('mouseup', handleMouseUp);
    return () => {
      document.removeEventListener('mousemove', handleMouseMove);
      document.removeEventListener('mouseup', handleMouseUp);
    };
  }, [handleMouseMove, handleMouseUp]);

  if (!selectionBounds) {
    return null;
  }

  return (
    <div
      ref={overlayRef}
      className={`pointer-events-none absolute rounded-md border-2 border-blue-500 ${className}`}
      style={{
        left: selectionBounds.x,
        top: selectionBounds.y,
        width: selectionBounds.width,
        height: selectionBounds.height,
      }}
      onMouseDown={handleMouseDown}
    >
      <div className="bg-opacity-20 absolute inset-0 rounded-md border border-blue-300 bg-blue-100" />
      <div className="pointer-events-auto absolute -top-10 left-1/2 flex -translate-x-1/2 items-center gap-2 rounded-md border border-gray-200 bg-white p-2 shadow-lg">
        <button
          onClick={onCopy}
          className="rounded p-1 text-gray-600 hover:bg-blue-50 hover:text-blue-600"
          title="复制选中的组件"
        >
          <Copy className="h-4 w-4" />
        </button>
        <button
          onClick={onDuplicate}
          className="rounded p-1 text-gray-600 hover:bg-blue-50 hover:text-blue-600"
          title="克隆选中的组件"
        >
          <Move className="h-4 w-4" />
        </button>
        <button
          onClick={onDelete}
          className="rounded p-1 text-red-600 hover:bg-red-50"
          title="删除选中的组件"
        >
          <Trash2 className="h-4 w-4" />
        </button>
        <button
          onClick={() => selectedComponents[0] && onOpenProperties(selectedComponents[0].id)}
          className="rounded p-1 text-gray-600 hover:bg-blue-50 hover:text-blue-600"
          title="打开属性面板"
        >
          <Settings className="h-4 w-4" />
        </button>
        <button
          onClick={onDuplicate}
          className="rounded p-1 text-gray-600 hover:bg-blue-50 hover:text-blue-600"
          title="撤销变更"
        >
          <RotateCcw className="h-4 w-4" />
        </button>
      </div>
    </div>
  );
}

export default SelectionOverlay;
