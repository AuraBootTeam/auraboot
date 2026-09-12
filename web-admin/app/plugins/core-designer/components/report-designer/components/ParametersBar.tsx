/**
 * ParametersBar — runtime parameter input form shown above report content
 * Allows users to fill in report parameters before viewing/exporting
 */

import React from 'react';
import { useSmartText } from '~/utils/i18n';
import type { ReportParameter } from '../types';

interface ParametersBarProps {
  parameters: ReportParameter[];
  values: Record<string, string>;
  onChange: (values: Record<string, string>) => void;
  onApply: () => void;
  disabled?: boolean;
}

export const ParametersBar: React.FC<ParametersBarProps> = ({
  parameters,
  values,
  onChange,
  onApply,
  disabled = false,
}) => {
  const text = useSmartText();
  if (parameters.length === 0) return null;

  const handleChange = (name: string, value: string) => {
    onChange({ ...values, [name]: value });
  };

  return (
    <div className="mb-6 rounded-lg border border-gray-200 bg-white p-4 print:hidden">
      <div className="flex flex-wrap items-end gap-4">
        {parameters.map((param) => (
          <div key={param.name} className="flex-shrink-0">
            <label className="mb-1 block text-xs font-medium text-gray-600">
              {param.label}
              {param.required && <span className="ml-0.5 text-red-500">*</span>}
            </label>
            {param.type === 'select' ? (
              <select
                aria-label={param.label}
                value={values[param.name] ?? param.defaultValue ?? ''}
                onChange={(e) => handleChange(param.name, e.target.value)}
                className="rounded-md border border-gray-300 px-3 py-1.5 text-sm focus:ring-2 focus:ring-blue-500 focus:outline-none"
              >
                <option value="">All</option>
                {param.options?.map((opt) => (
                  <option key={opt.value} value={opt.value}>
                    {opt.label}
                  </option>
                ))}
              </select>
            ) : param.type === 'date' ? (
              <input
                aria-label={param.label}
                type="date"
                value={values[param.name] ?? param.defaultValue ?? ''}
                onChange={(e) => handleChange(param.name, e.target.value)}
                className="rounded-md border border-gray-300 px-3 py-1.5 text-sm focus:ring-2 focus:ring-blue-500 focus:outline-none"
              />
            ) : param.type === 'date-range' ? (
              <div className="flex items-center gap-1">
                <input
                  type="date"
                  aria-label={`${param.label} start`}
                  value={values[`${param.name}_start`] || ''}
                  onChange={(e) => handleChange(`${param.name}_start`, e.target.value)}
                  className="rounded-md border border-gray-300 px-2 py-1.5 text-sm focus:ring-2 focus:ring-blue-500 focus:outline-none"
                />
                <span className="text-xs text-gray-400">to</span>
                <input
                  type="date"
                  aria-label={`${param.label} end`}
                  value={values[`${param.name}_end`] || ''}
                  onChange={(e) => handleChange(`${param.name}_end`, e.target.value)}
                  className="rounded-md border border-gray-300 px-2 py-1.5 text-sm focus:ring-2 focus:ring-blue-500 focus:outline-none"
                />
              </div>
            ) : param.type === 'number' ? (
              <input
                aria-label={param.label}
                type="number"
                value={values[param.name] ?? param.defaultValue ?? ''}
                onChange={(e) => handleChange(param.name, e.target.value)}
                className="w-32 rounded-md border border-gray-300 px-3 py-1.5 text-sm focus:ring-2 focus:ring-blue-500 focus:outline-none"
              />
            ) : (
              <input
                aria-label={param.label}
                type="text"
                value={values[param.name] ?? param.defaultValue ?? ''}
                onChange={(e) => handleChange(param.name, e.target.value)}
                className="w-48 rounded-md border border-gray-300 px-3 py-1.5 text-sm focus:ring-2 focus:ring-blue-500 focus:outline-none"
                placeholder={param.label}
              />
            )}
          </div>
        ))}
        <button
          onClick={onApply}
          disabled={disabled}
          className="flex-shrink-0 rounded-md bg-blue-600 px-4 py-1.5 text-sm text-white hover:bg-blue-700 disabled:opacity-50"
        >
          {text({ zh: '应用', en: 'Apply' })}
        </button>
      </div>
    </div>
  );
};
