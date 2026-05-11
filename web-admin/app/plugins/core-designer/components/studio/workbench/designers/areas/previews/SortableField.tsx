/**
 * SortableField
 *
 * A draggable field item within a block for reordering fields.
 */

import React from 'react';
import { useSortable } from '@dnd-kit/sortable';
import { CSS } from '@dnd-kit/utilities';
import { useI18n } from '~/contexts/I18nContext';

export interface SortableFieldProps {
  id: string;
  fieldName: string;
  placeholder?: string;
  required?: boolean;
  isDetailSection?: boolean;
  span?: number;
  columnsCount?: number;
  disabled?: boolean;
}

export const SortableField: React.FC<SortableFieldProps> = ({
  id,
  fieldName,
  placeholder,
  required,
  isDetailSection,
  span = 1,
  columnsCount = 2,
  disabled,
}) => {
  const { locale } = useI18n();
  const l = (zh: string, en: string) => (locale === 'zh-CN' ? zh : en);
  const { attributes, listeners, setNodeRef, transform, transition, isDragging } = useSortable({
    id,
    disabled,
    data: { type: 'field', fieldName },
  });

  const style: React.CSSProperties = {
    transform: CSS.Transform.toString(transform),
    transition,
    opacity: isDragging ? 0.5 : 1,
    gridColumn: `span ${Math.min(span, columnsCount)}`,
  };

  return (
    <div
      ref={setNodeRef}
      style={style}
      className={`group/field relative ${!disabled ? 'cursor-grab active:cursor-grabbing' : ''}`}
      {...attributes}
      {...listeners}
      onClick={(e) => e.stopPropagation()}
    >
      {/* Drag indicator on hover */}
      {!disabled && (
        <div className="pointer-events-none absolute top-1/2 -left-4 -translate-y-1/2 opacity-0 transition-opacity group-hover/field:opacity-100">
          <svg className="h-3 w-3 text-gray-400" viewBox="0 0 24 24" fill="currentColor">
            <circle cx="9" cy="7" r="1.5" />
            <circle cx="15" cy="7" r="1.5" />
            <circle cx="9" cy="12" r="1.5" />
            <circle cx="15" cy="12" r="1.5" />
            <circle cx="9" cy="17" r="1.5" />
            <circle cx="15" cy="17" r="1.5" />
          </svg>
        </div>
      )}

      <label className="mb-1 block text-xs text-gray-500">
        {fieldName}
        {required && <span className="ml-0.5 text-red-500">*</span>}
      </label>
      {isDetailSection ? (
        <div className="text-sm text-gray-900">--</div>
      ) : (
        <div
          className={`flex h-8 items-center rounded border bg-gray-50 px-2 text-sm text-gray-400 ${
            isDragging ? 'border-blue-300 bg-blue-50' : 'border-gray-200'
          }`}
        >
          {placeholder || l('请输入...', 'Enter...')}
        </div>
      )}
    </div>
  );
};

export default SortableField;
