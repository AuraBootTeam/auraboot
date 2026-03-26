/**
 * Model Picker — shared dropdown for selecting a meta model.
 * Used by Dashboard Designer, Report Designer, and any future designer that needs model selection.
 */

import React from 'react';
import { useMetaModels } from './useMetaModels';

export interface ModelPickerProps {
  value: string | undefined;
  onChange: (modelCode: string) => void;
  label?: string;
  required?: boolean;
  placeholder?: string;
  className?: string;
}

export const ModelPicker: React.FC<ModelPickerProps> = ({
  value,
  onChange,
  label = 'Model',
  required = false,
  placeholder = 'Select a model',
  className,
}) => {
  const { models, isLoading } = useMetaModels();

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
        {models.map((model) => (
          <option key={model.code} value={model.code}>
            {model.name} ({model.code})
          </option>
        ))}
      </select>
    </div>
  );
};
