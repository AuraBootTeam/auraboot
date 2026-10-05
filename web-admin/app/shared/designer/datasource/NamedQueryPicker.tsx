/**
 * Named Query Picker — shared dropdown for selecting a named query.
 * Used by Dashboard Designer, Report Designer, and any future designer.
 */

import React from 'react';
import { useNamedQueries } from './useMetaModels';
import { useSmartText } from '~/utils/i18n';

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
  const text = useSmartText();
  const { namedQueries, isLoading, error } = useNamedQueries();

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
        disabled={isLoading || !!error}
        className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm focus:ring-2 focus:ring-blue-500 focus:outline-none disabled:opacity-50"
      >
        <option value="">{isLoading ? 'Loading...' : placeholder}</option>
        {namedQueries.map((nq) => (
          <option key={nq.code} value={nq.code}>
            {nq.title} ({nq.code})
          </option>
        ))}
      </select>
      {error && (
        <p role="alert" className="mt-1 text-sm text-red-600">
          {text({
            zh: '命名查询加载失败，请刷新后重试。',
            en: 'Unable to load named queries. Refresh the page to retry.',
          })}
        </p>
      )}
    </div>
  );
};
