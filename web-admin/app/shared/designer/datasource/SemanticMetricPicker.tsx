/**
 * Semantic Metric Picker — multi-select for governed metrics declared in a
 * semantic model (PRD 16). Each option is a named metric code whose aggregation
 * formula lives in the *.semantic.yml definition, so — unlike the raw
 * MetricEditor — there is no per-metric aggregation dropdown here.
 *
 * Selected codes are surfaced as the dashboard widget's metric list; the
 * backend SemanticAggregateAdapter resolves each code against the model.
 */

import React from 'react';
import { useSemanticModelMeta } from './useMetaModels';

export interface SemanticMetricPickerProps {
  semanticModelCode: string | undefined;
  /** Selected metric codes */
  value: string[];
  onChange: (codes: string[]) => void;
  label?: string;
  required?: boolean;
  className?: string;
}

export const SemanticMetricPicker: React.FC<SemanticMetricPickerProps> = ({
  semanticModelCode,
  value,
  onChange,
  label = '语义指标',
  required = false,
  className,
}) => {
  const { metrics, isLoading } = useSemanticModelMeta(semanticModelCode);

  const renderLabel = () =>
    label && (
      <label className="mb-1 block text-sm font-medium text-gray-700">
        {label}
        {required && <span className="ml-0.5 text-red-500">*</span>}
      </label>
    );

  if (!semanticModelCode) {
    return (
      <div className={className} data-testid="semantic-metric-picker">
        {renderLabel()}
        <p className="text-sm text-gray-400">请先选择语义模型</p>
      </div>
    );
  }

  if (isLoading) {
    return (
      <div className={className} data-testid="semantic-metric-picker">
        {renderLabel()}
        <p className="text-sm text-gray-500">加载指标中…</p>
      </div>
    );
  }

  return (
    <div className={className} data-testid="semantic-metric-picker">
      {renderLabel()}
      {metrics.length === 0 ? (
        <p className="text-sm text-gray-400">该语义模型暂无指标</p>
      ) : (
        <div className="max-h-40 overflow-y-auto rounded-md border border-gray-300 p-2">
          {metrics.map((metric) => (
            <label
              key={metric.code}
              className="flex cursor-pointer items-center gap-2 py-1 text-sm"
            >
              <input
                type="checkbox"
                checked={value.includes(metric.code)}
                onChange={(e) => {
                  if (e.target.checked) {
                    onChange([...value, metric.code]);
                  } else {
                    onChange(value.filter((v) => v !== metric.code));
                  }
                }}
                className="rounded text-blue-600"
              />
              <span className="text-gray-800">{metric.name}</span>
              <span className="text-xs text-gray-400">({metric.code})</span>
              {metric.type && metric.type !== 'simple' && (
                <span className="rounded bg-blue-50 px-1 text-xs text-blue-600">{metric.type}</span>
              )}
            </label>
          ))}
        </div>
      )}
    </div>
  );
};
