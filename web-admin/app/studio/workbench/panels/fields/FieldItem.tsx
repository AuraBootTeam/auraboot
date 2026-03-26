import React, { useCallback } from 'react';
import { useFieldDrag } from '~/studio/hooks/fields/useFieldDrag';
import {
  VIRTUAL_BADGES,
  DATA_TYPE_COMPONENT_MAP,
  type MetaFieldDTO,
  type VirtualType,
} from './types';

interface FieldItemProps {
  field: MetaFieldDTO;
  /** Called when field is double-clicked to add to canvas */
  onDoubleClick?: (field: MetaFieldDTO) => void;
}

const DATA_TYPE_ICONS: Record<string, string> = {
  STRING: 'Aa',
  TEXT: 'Tx',
  INTEGER: '#',
  DECIMAL: '#.#',
  BOOLEAN: '?',
  DATE: 'D',
  DATETIME: 'DT',
  ENUM: 'E',
  REFERENCE: 'R',
  JSON: '{}',
  EMAIL: '@',
  PHONE: 'Ph',
  URL: '///',
};

/**
 * Single draggable field item in the Field Library Panel.
 * Shows field name, data type badge, and virtual field indicators.
 * Supports double-click to quickly add to canvas.
 *
 * @since 3.1.0
 */
export const FieldItem: React.FC<FieldItemProps> = ({ field, onDoubleClick }) => {
  const { attributes, listeners, setNodeRef, style, isDragging } = useFieldDrag({ field });

  const virtualBadge = field.virtualType ? VIRTUAL_BADGES[field.virtualType as VirtualType] : null;

  const dataTypeIcon = DATA_TYPE_ICONS[field.dataType] || '?';
  const componentType = DATA_TYPE_COMPONENT_MAP[field.dataType]?.type || 'input';

  const handleDoubleClick = useCallback(
    (e: React.MouseEvent) => {
      e.preventDefault();
      e.stopPropagation();
      onDoubleClick?.(field);
    },
    [field, onDoubleClick],
  );

  return (
    <div
      ref={setNodeRef}
      {...attributes}
      {...listeners}
      style={style}
      onDoubleClick={handleDoubleClick}
      className={`flex cursor-grab items-center gap-2 rounded-md border px-2.5 py-1.5 transition-all duration-150 select-none active:cursor-grabbing ${
        isDragging
          ? 'scale-[1.02] border-blue-400 bg-blue-50 opacity-50 shadow-lg'
          : 'border-gray-200 bg-white hover:border-blue-300 hover:bg-blue-50/50 hover:shadow-sm'
      } `}
      title={`${field.description || `${field.code} (${field.dataType})`}\n双击快速添加到画布`}
    >
      {/* Data type icon */}
      <span className="flex h-6 w-6 flex-shrink-0 items-center justify-center rounded bg-gray-100 font-mono text-[10px] text-gray-600">
        {dataTypeIcon}
      </span>

      {/* Field info */}
      <div className="min-w-0 flex-1">
        <div className="flex items-center gap-1">
          <span className="truncate text-xs font-medium text-gray-800">
            {field.displayName || field.code}
          </span>
          {field.required && <span className="text-[10px] text-red-500">*</span>}
          {/* Virtual type badge - inline */}
          {virtualBadge && (
            <span
              className={`flex-shrink-0 rounded px-1 py-px text-[10px] leading-tight font-medium ${virtualBadge.color === 'blue' ? 'bg-blue-100 text-blue-600' : ''} ${virtualBadge.color === 'green' ? 'bg-green-100 text-green-600' : ''} ${virtualBadge.color === 'amber' ? 'bg-amber-100 text-amber-600' : ''} `}
              title={`${virtualBadge.tooltip}${field.computeExpression ? ': ' + field.computeExpression : ''}`}
            >
              {virtualBadge.label}
            </span>
          )}
        </div>
        <div className="flex items-center gap-1">
          <span className="truncate text-[10px] text-gray-400">{field.code}</span>
          <span className="text-[10px] text-gray-300">·</span>
          <span className="text-[10px] text-gray-400">{componentType}</span>
        </div>
      </div>
    </div>
  );
};
