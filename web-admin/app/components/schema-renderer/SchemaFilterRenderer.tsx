/**
 * Schema Filter Region Renderer
 *
 * Renders the filter/search section of a schema-driven page,
 * including various filter field types and action buttons.
 */

import React, { useCallback } from 'react';
import type { FilterRendererProps, FilterField, DateRangeValue, LocalizedText } from './types';

/**
 * Renders a single filter field based on its type
 */
function FilterFieldInput({
  field,
  value,
  onChange,
  getLocalizedText,
}: {
  field: FilterField;
  value: unknown;
  onChange: (value: unknown) => void;
  getLocalizedText: (text: LocalizedText, fallback?: string) => string;
}) {
  const placeholder = getLocalizedText(field.props?.placeholder ?? '', '');

  const inputClassName =
    'w-full px-3 py-2 border border-gray-300 rounded-md focus:outline-none focus:ring-2 focus:ring-blue-500';

  switch (field.type) {
    case 'input':
      return (
        <input
          type="text"
          value={(value as string) ?? ''}
          onChange={(e) => onChange(e.target.value)}
          placeholder={placeholder}
          className={inputClassName}
          maxLength={field.props?.maxLength}
        />
      );

    case 'select':
      return (
        <select
          value={(value as string) ?? ''}
          onChange={(e) => onChange(e.target.value)}
          className={inputClassName}
        >
          <option value="">{placeholder}</option>
          {field.props?.options?.map((option) => (
            <option key={option.value} value={option.value}>
              {getLocalizedText(option.label, option.value)}
            </option>
          ))}
        </select>
      );

    case 'dateRange': {
      const rangeValue = (value as DateRangeValue) ?? {};
      return (
        <div className="flex space-x-2">
          <input
            type="date"
            value={rangeValue.start ?? ''}
            onChange={(e) => onChange({ ...rangeValue, start: e.target.value })}
            className={`flex-1 ${inputClassName}`}
          />
          <input
            type="date"
            value={rangeValue.end ?? ''}
            onChange={(e) => onChange({ ...rangeValue, end: e.target.value })}
            className={`flex-1 ${inputClassName}`}
          />
        </div>
      );
    }

    default:
      return (
        <input
          type="text"
          value={(value as string) ?? ''}
          onChange={(e) => onChange(e.target.value)}
          placeholder={placeholder}
          className={inputClassName}
        />
      );
  }
}

/**
 * Filter action buttons (Search & Reset)
 */
function FilterActions({ onSearch, onReset }: { onSearch: () => void; onReset: () => void }) {
  return (
    <div className="mt-4 flex justify-end space-x-2">
      <button
        onClick={onReset}
        className="rounded-md border border-gray-300 bg-white px-4 py-2 text-gray-600 hover:bg-gray-50"
      >
        Reset
      </button>
      <button
        onClick={onSearch}
        className="rounded-md bg-blue-600 px-4 py-2 text-white hover:bg-blue-700"
      >
        Search
      </button>
    </div>
  );
}

/**
 * Schema Filter Region Renderer
 *
 * Renders a grid of filter fields with search/reset actions.
 */
export function SchemaFilterRenderer({
  region,
  filters,
  onFilterChange,
  onSearch,
  onReset,
  getLocalizedText,
}: FilterRendererProps) {
  const handleFieldChange = useCallback(
    (fieldCode: string) => (value: unknown) => {
      onFilterChange(fieldCode, value);
    },
    [onFilterChange],
  );

  if (!region.fields || region.fields.length === 0) {
    return null;
  }

  return (
    <div className="mb-6 rounded-lg bg-gray-50 p-4">
      <div className="grid grid-cols-1 gap-4 md:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4">
        {region.fields.map((field, index) => (
          <div key={field.code || index} className="space-y-1">
            {field.label && (
              <label className="block text-sm font-medium text-gray-700">
                {getLocalizedText(field.label, field.code)}
              </label>
            )}
            <FilterFieldInput
              field={field}
              value={filters[field.code]}
              onChange={handleFieldChange(field.code)}
              getLocalizedText={getLocalizedText}
            />
          </div>
        ))}
      </div>
      <FilterActions onSearch={onSearch} onReset={onReset} />
    </div>
  );
}

export default SchemaFilterRenderer;
