/**
 * Metric Editor — shared component for configuring aggregation metrics.
 * Each metric row: field dropdown + aggregation dropdown + remove button.
 * Used in Dashboard DataSourceConfig, Report DataSourceConfig, etc.
 */

import React, { useCallback } from 'react';
import { useModelFields } from './useMetaModels';
import { AGGREGATION_FUNCTIONS } from './types';
import { Plus, X } from 'lucide-react';

export interface MetricConfig {
  field: string;
  aggregation: string;
}

export interface MetricEditorProps {
  metrics: MetricConfig[];
  onChange: (metrics: MetricConfig[]) => void;
  modelCode?: string;
  label?: string;
  required?: boolean;
  className?: string;
}

export const MetricEditor: React.FC<MetricEditorProps> = ({
  metrics,
  onChange,
  modelCode,
  label = 'Metrics',
  required = false,
  className,
}) => {
  const { fields, isLoading } = useModelFields(modelCode);

  const addMetric = useCallback(() => {
    onChange([...metrics, { field: '', aggregation: 'count' }]);
  }, [metrics, onChange]);

  const removeMetric = useCallback(
    (index: number) => {
      onChange(metrics.filter((_, i) => i !== index));
    },
    [metrics, onChange],
  );

  const updateMetric = useCallback(
    (index: number, key: keyof MetricConfig, value: string) => {
      const updated = [...metrics];
      updated[index] = { ...updated[index], [key]: value };
      onChange(updated);
    },
    [metrics, onChange],
  );

  const canRemove = !(required && metrics.length <= 1);

  return (
    <div className={className}>
      <div className="mb-1 flex items-center justify-between">
        <label className="block text-sm font-medium text-gray-700">
          {label} {required && <span className="text-red-500">*</span>}
        </label>
        <button
          type="button"
          onClick={addMetric}
          className="flex items-center gap-1 text-xs text-blue-600 hover:text-blue-800"
        >
          <Plus className="h-3 w-3" />
          Add Metric
        </button>
      </div>

      {isLoading ? (
        <p className="text-sm text-gray-500">Loading fields...</p>
      ) : (
        <div className="space-y-2">
          {metrics.map((metric, index) => (
            <div key={index} className="flex items-center gap-2 rounded bg-gray-50 p-2">
              <select
                value={metric.field}
                onChange={(e) => updateMetric(index, 'field', e.target.value)}
                className="flex-1 rounded border border-gray-300 px-2 py-1 text-sm"
              >
                <option value="">Select field</option>
                {fields.map((field) => (
                  <option key={field.code} value={field.code}>
                    {field.name}
                  </option>
                ))}
              </select>
              <select
                value={metric.aggregation}
                onChange={(e) => updateMetric(index, 'aggregation', e.target.value)}
                className="w-32 rounded border border-gray-300 px-2 py-1 text-sm"
              >
                {AGGREGATION_FUNCTIONS.map((opt) => (
                  <option key={opt.value} value={opt.value}>
                    {opt.label}
                  </option>
                ))}
              </select>
              <button
                type="button"
                onClick={() => removeMetric(index)}
                className="p-1 text-gray-400 hover:text-red-500 disabled:cursor-not-allowed disabled:opacity-30"
                disabled={!canRemove}
              >
                <X className="h-4 w-4" />
              </button>
            </div>
          ))}
        </div>
      )}
    </div>
  );
};
