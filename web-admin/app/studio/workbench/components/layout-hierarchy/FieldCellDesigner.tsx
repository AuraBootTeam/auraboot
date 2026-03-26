import React from 'react';
import type { FieldCellConfig } from '~/studio/domain/schema/layout-hierarchy';

interface FieldCellDesignerProps {
  field: FieldCellConfig;
  selected: boolean;
  onSelect: () => void;
  onRemove: () => void;
  onUpdate: (updates: Partial<FieldCellConfig>) => void;
}

/**
 * Field Cell Designer - renders a single field in design mode.
 * Shows field code, component type, and provides selection/removal.
 */
export const FieldCellDesigner: React.FC<FieldCellDesignerProps> = ({
  field,
  selected,
  onSelect,
  onRemove,
  onUpdate,
}) => {
  return (
    <div
      onClick={(e) => {
        e.stopPropagation();
        onSelect();
      }}
      className={`group relative cursor-pointer rounded border px-3 py-2 transition-all ${
        selected
          ? 'border-blue-500 bg-blue-50 ring-1 ring-blue-200'
          : 'border-gray-200 bg-white hover:border-blue-300 hover:bg-gray-50'
      } `}
      style={{ gridColumn: field.span ? `span ${field.span}` : undefined }}
    >
      <div className="flex items-center justify-between">
        <div className="flex min-w-0 items-center gap-2">
          <span className="shrink-0 font-mono text-xs text-gray-400">
            {field.componentType.replace('Smart', '')}
          </span>
          <span className="truncate text-sm text-gray-700">{field.label || field.fieldCode}</span>
        </div>

        <button
          onClick={(e) => {
            e.stopPropagation();
            onRemove();
          }}
          className="p-0.5 text-gray-400 opacity-0 transition-opacity group-hover:opacity-100 hover:text-red-500"
          title="移除字段"
        >
          <svg className="h-3.5 w-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path
              strokeLinecap="round"
              strokeLinejoin="round"
              strokeWidth={2}
              d="M6 18L18 6M6 6l12 12"
            />
          </svg>
        </button>
      </div>

      {field.required && (
        <span className="absolute -top-1 -right-1 h-2 w-2 rounded-full bg-red-400" title="必填" />
      )}
    </div>
  );
};
