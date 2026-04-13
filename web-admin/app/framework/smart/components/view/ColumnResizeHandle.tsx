/**
 * ColumnResizeHandle — Draggable handle for resizing table columns.
 *
 * Rendered inside <th> elements. On drag, updates column width in real-time
 * and calls onResize when the drag ends.
 */
import React, { useCallback, useRef } from 'react';

interface ColumnResizeHandleProps {
  /** Current column width in px */
  width: number;
  /** Minimum allowed width */
  minWidth?: number;
  /** Maximum allowed width */
  maxWidth?: number;
  /** Called when resize completes with the new width */
  onResize: (newWidth: number) => void;
}

export const ColumnResizeHandle = React.memo(function ColumnResizeHandle({
  width,
  minWidth = 50,
  maxWidth = 800,
  onResize,
}: ColumnResizeHandleProps) {
  const startXRef = useRef(0);
  const startWidthRef = useRef(0);
  const isDraggingRef = useRef(false);

  const handleMouseDown = useCallback(
    (e: React.MouseEvent) => {
      e.preventDefault();
      e.stopPropagation();
      isDraggingRef.current = true;
      startXRef.current = e.clientX;
      startWidthRef.current = width;

      // Find the <th> element to update width during drag
      const th = (e.target as HTMLElement).closest('th');
      if (!th) return;

      const handleMouseMove = (moveEvent: MouseEvent) => {
        if (!isDraggingRef.current) return;
        const diff = moveEvent.clientX - startXRef.current;
        const newWidth = Math.min(maxWidth, Math.max(minWidth, startWidthRef.current + diff));
        th.style.width = `${newWidth}px`;
      };

      const handleMouseUp = (upEvent: MouseEvent) => {
        if (!isDraggingRef.current) return;
        isDraggingRef.current = false;
        document.removeEventListener('mousemove', handleMouseMove);
        document.removeEventListener('mouseup', handleMouseUp);
        document.body.style.cursor = '';
        document.body.style.userSelect = '';

        const diff = upEvent.clientX - startXRef.current;
        const newWidth = Math.min(maxWidth, Math.max(minWidth, startWidthRef.current + diff));
        if (newWidth !== startWidthRef.current) {
          onResize(newWidth);
        }
      };

      document.addEventListener('mousemove', handleMouseMove);
      document.addEventListener('mouseup', handleMouseUp);
      document.body.style.cursor = 'col-resize';
      document.body.style.userSelect = 'none';
    },
    [width, minWidth, maxWidth, onResize],
  );

  return (
    <div
      className="absolute top-0 right-0 h-full w-1 cursor-col-resize opacity-0 transition-opacity group-hover/th:opacity-50 hover:bg-blue-400 hover:opacity-100"
      onMouseDown={handleMouseDown}
      role="separator"
      aria-orientation="vertical"
    />
  );
});
