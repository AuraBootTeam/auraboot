/**
 * Field Selector — shared multi-select for model fields.
 * Used for dimension selection (Dashboard), column selection (Report), etc.
 */

import React from 'react';
import { useModelFields } from './useMetaModels';
import type { FieldOption } from './types';

export interface FieldSelectorProps {
  modelCode: string | undefined;
  value: string[];
  onChange: (selected: string[]) => void;
  label?: string;
  placeholder?: string;
  /** Filter fields by type (e.g. only numeric for metrics) */
  fieldTypeFilter?: (field: FieldOption) => boolean;
  /** When true, render radio buttons for single selection instead of checkboxes */
  single?: boolean;
  className?: string;
}

export const FieldSelector: React.FC<FieldSelectorProps> = ({
  modelCode,
  value,
  onChange,
  label = 'Fields',
  placeholder = 'Select fields',
  fieldTypeFilter,
  single = false,
  className,
}) => {
  const { fields, isLoading } = useModelFields(modelCode);

  const filteredFields = fieldTypeFilter ? fields.filter(fieldTypeFilter) : fields;

  if (!modelCode) {
    return (
      <div className={className}>
        {label && <label className="mb-1 block text-sm font-medium text-gray-700">{label}</label>}
        <p className="text-sm text-gray-400">Select a model first</p>
      </div>
    );
  }

  if (isLoading) {
    return (
      <div className={className}>
        {label && <label className="mb-1 block text-sm font-medium text-gray-700">{label}</label>}
        <p className="text-sm text-gray-500">Loading fields...</p>
      </div>
    );
  }

  return (
    <div className={className}>
      {label && <label className="mb-1 block text-sm font-medium text-gray-700">{label}</label>}
      {filteredFields.length === 0 ? (
        <p className="text-sm text-gray-400">No fields available</p>
      ) : (
        <div className="max-h-40 overflow-y-auto rounded-md border border-gray-300 p-2">
          {filteredFields.map((field) => (
            <label key={field.code} className="flex cursor-pointer items-center gap-2 py-1 text-sm">
              <input
                type={single ? 'radio' : 'checkbox'}
                name={single ? `field-selector-${label}` : undefined}
                checked={value.includes(field.code)}
                onChange={(e) => {
                  if (single) {
                    onChange([field.code]);
                  } else if (e.target.checked) {
                    onChange([...value, field.code]);
                  } else {
                    onChange(value.filter((v) => v !== field.code));
                  }
                }}
                className="rounded text-blue-600"
              />
              <span className="text-gray-800">{field.name}</span>
              <span className="text-xs text-gray-400">({field.code})</span>
            </label>
          ))}
        </div>
      )}
    </div>
  );
};
