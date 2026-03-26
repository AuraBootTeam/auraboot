/**
 * SortableSubTableRow — a sub-table row wrapped with @dnd-kit sortable.
 *
 * Features:
 * - Drag handle (⠿ icon)
 * - Tree indent (padding-left based on depth)
 * - Expand/collapse toggle (▶/▼)
 */

import React from 'react';
import { useSortable } from '@dnd-kit/sortable';
import { CSS } from '@dnd-kit/utilities';

export interface SortableSubTableRowProps {
  id: string;
  depth?: number;
  hasChildren?: boolean;
  expanded?: boolean;
  onToggleExpand?: () => void;
  sortable?: boolean;
  children: React.ReactNode;
}

const INDENT_WIDTH = 24;

export const SortableSubTableRow: React.FC<SortableSubTableRowProps> = ({
  id,
  depth = 0,
  hasChildren = false,
  expanded = false,
  onToggleExpand,
  sortable = true,
  children,
}) => {
  const { attributes, listeners, setNodeRef, transform, transition, isDragging } = useSortable({
    id,
    disabled: !sortable,
  });

  const style: React.CSSProperties = {
    transform: CSS.Transform.toString(transform),
    transition,
    opacity: isDragging ? 0.5 : 1,
  };

  return (
    <tr
      ref={setNodeRef}
      style={style}
      className={`${isDragging ? 'bg-blue-50' : ''}`}
      data-testid={`sortable-row-${id}`}
    >
      {/* Drag handle + tree indent cell */}
      {sortable && (
        <td className="w-10 px-1" style={{ paddingLeft: depth * INDENT_WIDTH + 4 }}>
          <div className="flex items-center gap-1">
            {/* Expand/collapse toggle */}
            {hasChildren ? (
              <button
                type="button"
                onClick={onToggleExpand}
                className="flex h-4 w-4 items-center justify-center text-gray-400 hover:text-gray-600"
                data-testid={`tree-toggle-${id}`}
              >
                {expanded ? '▼' : '▶'}
              </button>
            ) : (
              <span className="w-4" /> // Spacer for alignment
            )}
            {/* Drag handle */}
            <button
              type="button"
              className="cursor-grab text-gray-300 hover:text-gray-500"
              {...attributes}
              {...listeners}
              data-testid={`drag-handle-${id}`}
            >
              ⠿
            </button>
          </div>
        </td>
      )}
      {children}
    </tr>
  );
};
