/**
 * ColumnResizer — drag handle for resizing table columns
 *
 * Renders a thin draggable handle on the right edge of table headers.
 * Reports the new width via onResize callback.
 */

import React, { useRef, useCallback } from 'react';

export interface ColumnResizerProps {
  /** Minimum column width in pixels */
  minWidth?: number;
  /** Maximum column width in pixels */
  maxWidth?: number;
  /** Called with new width when resize completes */
  onResize: (width: number) => void;
  /** Current column width */
  currentWidth: number;
}

export const ColumnResizer: React.FC<ColumnResizerProps> = ({
  minWidth = 60,
  maxWidth = 600,
  onResize,
  currentWidth,
}) => {
  const startXRef = useRef(0);
  const startWidthRef = useRef(0);

  const handleMouseDown = useCallback(
    (e: React.MouseEvent) => {
      e.preventDefault();
      e.stopPropagation();

      startXRef.current = e.clientX;
      startWidthRef.current = currentWidth;

      const handleMouseMove = (moveEvent: MouseEvent) => {
        const diff = moveEvent.clientX - startXRef.current;
        const newWidth = Math.min(maxWidth, Math.max(minWidth, startWidthRef.current + diff));
        onResize(newWidth);
      };

      const handleMouseUp = () => {
        document.removeEventListener('mousemove', handleMouseMove);
        document.removeEventListener('mouseup', handleMouseUp);
        document.body.style.cursor = '';
        document.body.style.userSelect = '';
      };

      document.addEventListener('mousemove', handleMouseMove);
      document.addEventListener('mouseup', handleMouseUp);
      document.body.style.cursor = 'col-resize';
      document.body.style.userSelect = 'none';
    },
    [currentWidth, minWidth, maxWidth, onResize],
  );

  return (
    <div
      onMouseDown={handleMouseDown}
      className="group absolute top-0 right-0 bottom-0 w-1 cursor-col-resize transition-colors hover:bg-blue-400 active:bg-blue-500"
      role="separator"
      aria-orientation="vertical"
    >
      <div className="absolute top-1/2 right-0 h-4 w-0.5 -translate-y-1/2 rounded-full bg-gray-300 group-hover:bg-blue-400" />
    </div>
  );
};
