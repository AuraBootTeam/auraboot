/**
 * Filter Builder — shared component for building filter conditions.
 * Used by Dashboard DataSourceConfig and Report block editors.
 */

import React from 'react';
import type { FilterCondition, FieldOption } from './types';
import { FILTER_OPERATORS } from './types';
import { Plus, X } from 'lucide-react';

export interface FilterBuilderProps {
  value: FilterCondition[];
  onChange: (filters: FilterCondition[]) => void;
  /** Available fields for the field dropdown. If not provided, uses free-text input. */
  fields?: FieldOption[];
  label?: string;
  className?: string;
}

export const FilterBuilder: React.FC<FilterBuilderProps> = ({
  value,
  onChange,
  fields,
  label = 'Filters',
  className,
}) => {
  const addFilter = () => {
    onChange([...value, { field: '', operator: 'eq', value: '' }]);
  };

  const removeFilter = (index: number) => {
    onChange(value.filter((_, i) => i !== index));
  };

  const updateFilter = (index: number, key: keyof FilterCondition, val: string) => {
    const updated = [...value];
    updated[index] = { ...updated[index], [key]: val };
    onChange(updated);
  };

  return (
    <div className={className}>
      {label && (
        <div className="mb-2 flex items-center justify-between">
          <label className="text-sm font-medium text-gray-700">{label}</label>
          <button
            type="button"
            onClick={addFilter}
            className="flex items-center gap-1 text-xs text-blue-600 hover:text-blue-700"
          >
            <Plus className="h-3 w-3" />
            Add
          </button>
        </div>
      )}

      {value.length === 0 && <p className="text-xs text-gray-400">No filters configured</p>}

      <div className="space-y-2">
        {value.map((filter, index) => (
          <div key={index} className="flex items-center gap-2">
            {/* Field */}
            {fields ? (
              <select
                value={filter.field}
                onChange={(e) => updateFilter(index, 'field', e.target.value)}
                className="flex-1 rounded-md border border-gray-300 px-2 py-1.5 text-xs focus:ring-1 focus:ring-blue-500 focus:outline-none"
              >
                <option value="">Field</option>
                {fields.map((f) => (
                  <option key={f.code} value={f.code}>
                    {f.name}
                  </option>
                ))}
              </select>
            ) : (
              <input
                type="text"
                value={filter.field}
                onChange={(e) => updateFilter(index, 'field', e.target.value)}
                placeholder="Field"
                className="flex-1 rounded-md border border-gray-300 px-2 py-1.5 text-xs focus:ring-1 focus:ring-blue-500 focus:outline-none"
              />
            )}

            {/* Operator */}
            <select
              value={filter.operator}
              onChange={(e) => updateFilter(index, 'operator', e.target.value)}
              className="w-28 rounded-md border border-gray-300 px-2 py-1.5 text-xs focus:ring-1 focus:ring-blue-500 focus:outline-none"
            >
              {FILTER_OPERATORS.map((op) => (
                <option key={op.value} value={op.value}>
                  {op.label}
                </option>
              ))}
            </select>

            {/* Value (hidden for isNull/isNotNull) */}
            {filter.operator !== 'isNull' && filter.operator !== 'isNotNull' && (
              <input
                type="text"
                value={filter.value}
                onChange={(e) => updateFilter(index, 'value', e.target.value)}
                placeholder="Value"
                className="flex-1 rounded-md border border-gray-300 px-2 py-1.5 text-xs focus:ring-1 focus:ring-blue-500 focus:outline-none"
              />
            )}

            {/* Remove */}
            <button
              type="button"
              onClick={() => removeFilter(index)}
              className="rounded p-1 text-gray-400 hover:bg-gray-100 hover:text-red-500"
            >
              <X className="h-3.5 w-3.5" />
            </button>
          </div>
        ))}
      </div>
    </div>
  );
};
