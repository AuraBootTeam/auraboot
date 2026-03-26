/**
 * Named Query Picker — shared dropdown for selecting a named query.
 * Used by Dashboard Designer, Report Designer, and any future designer.
 */

import React from 'react';
import { useNamedQueries } from './useMetaModels';

export interface NamedQueryPickerProps {
  value: string | undefined;
  onChange: (queryCode: string) => void;
  label?: string;
  required?: boolean;
  placeholder?: string;
  className?: string;
}

export const NamedQueryPicker: React.FC<NamedQueryPickerProps> = ({
  value,
  onChange,
  label = 'Named Query',
  required = false,
  placeholder = 'Select a named query',
  className,
}) => {
  const { namedQueries, isLoading } = useNamedQueries();

  return (
    <div className={className}>
      {label && (
        <label className="mb-1 block text-sm font-medium text-gray-700">
          {label}
          {required && <span className="text-red-500"> *</span>}
        </label>
      )}
      <select
        value={value || ''}
        onChange={(e) => onChange(e.target.value)}
        disabled={isLoading}
        className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm focus:ring-2 focus:ring-blue-500 focus:outline-none disabled:opacity-50"
      >
        <option value="">{isLoading ? 'Loading...' : placeholder}</option>
        {namedQueries.map((nq) => (
          <option key={nq.code} value={nq.code}>
            {nq.title} ({nq.code})
          </option>
        ))}
      </select>
    </div>
  );
};
