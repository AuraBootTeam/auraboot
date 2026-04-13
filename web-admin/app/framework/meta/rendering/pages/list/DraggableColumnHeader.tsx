/**
 * DraggableColumnHeader — Table column header (<th>) with three interaction zones:
 *
 * 1. Left: Drag handle (visible on hover) — uses useSortable from @dnd-kit/sortable
 * 2. Center: Label + SortIndicator — click toggles sort, Shift+click for multi-sort
 * 3. Right: Resize handle — uses existing ColumnResizeHandle component
 *
 * When dragging, the header gets a blue tint + shadow + reduced opacity.
 */
import React, { useCallback } from 'react';
import { useSortable } from '@dnd-kit/sortable';
import { CSS } from '@dnd-kit/utilities';
import { SortIndicator } from './SortIndicator';
import { ColumnResizeHandle } from '~/framework/smart/components/view/ColumnResizeHandle';
import type { ColumnConfig } from '~/framework/meta/schemas/types';

export interface DraggableColumnHeaderProps {
  column: ColumnConfig;
  label: string;
  sortable: boolean;
  sortInfo?: { direction: 'asc' | 'desc'; priority?: number };
  onSort: (field: string, multiSort: boolean) => void;
  onResize: (field: string, width: number) => void;
  onContextMenu: (e: React.MouseEvent, column: ColumnConfig) => void;
  draggable?: boolean;
  width?: number | string;
}

export const DraggableColumnHeader = React.memo(function DraggableColumnHeader({
  column,
  label,
  sortable,
  sortInfo,
  onSort,
  onResize,
  onContextMenu,
  draggable = true,
  width,
}: DraggableColumnHeaderProps) {
  const {
    attributes,
    listeners,
    setNodeRef,
    transform,
    transition,
    isDragging,
  } = useSortable({
    id: column.field,
    disabled: !draggable,
  });

  const frozenPos = column.fixed || (column as any).frozenPosition;
  const isFrozenLeft = frozenPos === 'left';
  const isFrozenRight = frozenPos === 'right' || column.isActionColumn;

  const currentWidth =
    typeof width === 'number'
      ? width
      : typeof width === 'string'
        ? parseInt(width, 10) || 100
        : typeof column.width === 'number'
          ? column.width
          : typeof column.width === 'string'
            ? parseInt(column.width, 10) || 100
            : 100;

  const handleSortClick = useCallback(
    (e: React.MouseEvent) => {
      if (!sortable) return;
      onSort(column.field, e.shiftKey);
    },
    [sortable, column.field, onSort],
  );

  const handleContextMenu = useCallback(
    (e: React.MouseEvent) => {
      if (column.isActionColumn) return;
      e.preventDefault();
      e.stopPropagation();
      onContextMenu(e, column);
    },
    [column, onContextMenu],
  );

  const handleResizeComplete = useCallback(
    (newWidth: number) => {
      onResize(column.field, newWidth);
    },
    [column.field, onResize],
  );

  const style: React.CSSProperties = {
    transform: CSS.Transform.toString(transform),
    transition,
    ...(isDragging
      ? { opacity: 0.6, backgroundColor: '#dbeafe', boxShadow: '0 4px 12px rgba(0,0,0,0.15)', zIndex: 50 }
      : {}),
    ...(width
      ? { width: typeof width === 'number' ? `${width}px` : width }
      : column.width
        ? { width: typeof column.width === 'number' ? `${column.width}px` : column.width }
        : {}),
  };

  // Action column: no drag, no sort, no resize
  if (column.isActionColumn) {
    return (
      <th
        className="sticky right-0 z-20 w-px border-l border-gray-200 bg-gray-50 px-2 py-3 text-xs font-medium tracking-wider text-gray-500 uppercase shadow-[-8px_0_8px_-8px_rgba(0,0,0,0.2)]"
      >
        {label}
      </th>
    );
  }

  return (
    <th
      ref={setNodeRef}
      className={`group/th relative bg-gray-50 px-6 py-3 text-xs font-medium tracking-wider text-gray-500 uppercase ${
        column.align === 'right'
          ? 'text-right'
          : column.align === 'center'
            ? 'text-center'
            : 'text-left'
      } ${
        isFrozenRight
          ? 'sticky right-0 z-20 border-l border-gray-200 bg-gray-50 shadow-[-8px_0_8px_-8px_rgba(0,0,0,0.2)]'
          : isFrozenLeft
            ? 'sticky left-0 z-20 border-r border-gray-200 bg-gray-50 shadow-[2px_0_4px_-2px_rgba(0,0,0,0.1)]'
            : ''
      } ${sortable ? 'select-none' : ''}`}
      style={style}
      onContextMenu={handleContextMenu}
    >
      <div className="flex items-center gap-1">
        {/* Drag handle — visible on hover */}
        {draggable && (
          <span
            {...attributes}
            {...listeners}
            className="flex-shrink-0 cursor-grab opacity-0 transition-opacity group-hover/th:opacity-60 hover:opacity-100 active:cursor-grabbing"
            aria-label="Drag to reorder column"
            onClick={(e) => e.stopPropagation()}
          >
            <svg width="10" height="14" viewBox="0 0 10 14" fill="currentColor" className="text-gray-400">
              <circle cx="3" cy="2" r="1.2" />
              <circle cx="7" cy="2" r="1.2" />
              <circle cx="3" cy="7" r="1.2" />
              <circle cx="7" cy="7" r="1.2" />
              <circle cx="3" cy="12" r="1.2" />
              <circle cx="7" cy="12" r="1.2" />
            </svg>
          </span>
        )}

        {/* Label + sort indicator — click to toggle sort */}
        <span
          className={`flex flex-1 cursor-pointer items-center gap-1 ${sortable ? 'hover:text-gray-700' : ''}`}
          onClick={handleSortClick}
        >
          <span className="truncate">{label}</span>
          {sortable && (
            <span
              className={`transition-opacity ${sortInfo ? 'opacity-100' : 'opacity-0 group-hover/th:opacity-40'}`}
            >
              <SortIndicator
                direction={sortInfo?.direction}
                priority={sortInfo?.priority}
              />
            </span>
          )}
        </span>

        {/* Column context menu trigger */}
        <button
          type="button"
          className="absolute top-1/2 right-1 -translate-y-1/2 rounded p-0.5 text-gray-400 opacity-0 transition-opacity group-hover/th:opacity-100 hover:bg-gray-200 hover:text-gray-600"
          onClick={(e) => {
            e.stopPropagation();
            onContextMenu(e, column);
          }}
        >
          <svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor">
            <circle cx="12" cy="5" r="2" />
            <circle cx="12" cy="12" r="2" />
            <circle cx="12" cy="19" r="2" />
          </svg>
        </button>
      </div>

      {/* Resize handle */}
      <ColumnResizeHandle
        width={currentWidth}
        onResize={handleResizeComplete}
      />
    </th>
  );
});
