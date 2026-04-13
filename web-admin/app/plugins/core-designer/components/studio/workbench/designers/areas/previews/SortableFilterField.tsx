/**
 * SortableFilterField
 *
 * A draggable filter field item for reordering filter fields.
 */

import React from 'react';
import { useSortable } from '@dnd-kit/sortable';
import { CSS } from '@dnd-kit/utilities';

export interface SortableFilterFieldProps {
  id: string;
  fieldName: string;
  placeholder?: string;
  isAdvanced?: boolean;
  isSelected?: boolean;
  disabled?: boolean;
  onSelect?: () => void;
}

export const SortableFilterField: React.FC<SortableFilterFieldProps> = ({
  id,
  fieldName,
  placeholder,
  isAdvanced,
  isSelected,
  disabled,
  onSelect,
}) => {
  const { attributes, listeners, setNodeRef, transform, transition, isDragging } = useSortable({
    id,
    disabled,
    data: { type: 'filter-field', fieldName },
  });

  const style: React.CSSProperties = {
    transform: CSS.Transform.toString(transform),
    transition: transition || 'transform 200ms ease, box-shadow 200ms ease',
  };

  // Handle click for selection (separate from drag)
  const handleClick = (e: React.MouseEvent) => {
    e.stopPropagation();
    if (!isDragging && onSelect) {
      onSelect();
    }
  };

  return (
    <div
      ref={setNodeRef}
      style={style}
      className={`group/filter relative transition-all duration-200 ${
        isAdvanced && !isDragging ? 'opacity-60' : ''
      } ${!disabled ? 'cursor-pointer' : ''} ${
        isSelected ? 'rounded-lg bg-blue-50/50 ring-2 ring-blue-500' : ''
      } ${
        isDragging
          ? 'z-50 scale-105 rounded-lg bg-white opacity-80 shadow-lg ring-2 ring-blue-400'
          : ''
      }`}
      {...attributes}
      {...listeners}
      onClick={handleClick}
    >
      {/* Drag indicator on hover */}
      {!disabled && (
        <div
          className={`pointer-events-none absolute top-1/2 -left-5 -translate-y-1/2 rounded p-1 transition-all duration-200 ${
            isDragging
              ? 'bg-blue-100 text-blue-500 opacity-100'
              : 'text-gray-400 opacity-0 group-hover/filter:opacity-100'
          }`}
        >
          <svg className="h-3 w-3" viewBox="0 0 24 24" fill="currentColor">
            <circle cx="9" cy="7" r="1.5" />
            <circle cx="15" cy="7" r="1.5" />
            <circle cx="9" cy="12" r="1.5" />
            <circle cx="15" cy="12" r="1.5" />
            <circle cx="9" cy="17" r="1.5" />
            <circle cx="15" cy="17" r="1.5" />
          </svg>
        </div>
      )}
      {/* Hover highlight */}
      {!disabled && !isDragging && (
        <div className="pointer-events-none absolute inset-0 rounded border border-transparent transition-colors group-hover/filter:border-blue-200" />
      )}

      <label className="mb-1 block truncate text-xs text-gray-500">
        {fieldName}
        {isAdvanced && <span className="ml-1 text-[10px] text-gray-400">(高级)</span>}
      </label>
      <div
        className={`flex h-8 items-center rounded border bg-white px-2 text-sm text-gray-400 ${
          isDragging ? 'border-blue-300 bg-blue-50' : 'border-gray-200'
        }`}
      >
        {placeholder || '请输入...'}
      </div>
    </div>
  );
};

export default SortableFilterField;
