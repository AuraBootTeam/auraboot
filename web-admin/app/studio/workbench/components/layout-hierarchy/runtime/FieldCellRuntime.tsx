import React from 'react';
import type { FieldCellConfig } from '~/studio/domain/schema/layout-hierarchy';

interface FieldCellRuntimeProps {
  field: FieldCellConfig;
  data?: Record<string, any>;
}

/**
 * Field Cell Runtime - renders a field in preview/runtime mode.
 * Displays as a labeled form field with the mapped component type.
 */
export const FieldCellRuntime: React.FC<FieldCellRuntimeProps> = ({ field, data }) => {
  const value = data?.[field.fieldCode] ?? '';

  return (
    <div
      className="flex flex-col gap-1"
      style={{ gridColumn: field.span ? `span ${field.span}` : undefined }}
    >
      <label className="text-sm font-medium text-gray-700">
        {field.label || field.fieldCode}
        {field.required && <span className="ml-0.5 text-red-500">*</span>}
      </label>
      <div className="rounded-md border border-gray-300 bg-white px-3 py-2 text-sm text-gray-900">
        {value || <span className="text-gray-400">-</span>}
      </div>
    </div>
  );
};
