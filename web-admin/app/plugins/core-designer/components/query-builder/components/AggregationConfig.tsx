/**
 * AggregationConfig — Configure GROUP BY and aggregate functions
 */

import type { AggregationConfig as AggConfig } from '../services/queryBuilderService';

const AGG_FUNCTIONS = ['count', 'sum', 'avg', 'min', 'max'] as const;

interface AggregationConfigProps {
  groupBy: string[];
  aggregations: AggConfig[];
  availableFields: string[];
  onGroupByChange: (fields: string[]) => void;
  onAggregationsChange: (aggs: AggConfig[]) => void;
}

export const AggregationConfig: React.FC<AggregationConfigProps> = ({
  groupBy,
  aggregations,
  availableFields,
  onGroupByChange,
  onAggregationsChange,
}) => {
  const toggleGroupBy = (field: string) => {
    if (groupBy.includes(field)) {
      onGroupByChange(groupBy.filter((f) => f !== field));
    } else {
      onGroupByChange([...groupBy, field]);
    }
  };

  const addAggregation = () => {
    onAggregationsChange([
      ...aggregations,
      { fieldCode: availableFields[0] || '', function: 'count', alias: '' },
    ]);
  };

  const updateAgg = (index: number, patch: Partial<AggConfig>) => {
    onAggregationsChange(aggregations.map((a, i) => (i === index ? { ...a, ...patch } : a)));
  };

  const removeAgg = (index: number) => {
    onAggregationsChange(aggregations.filter((_, i) => i !== index));
  };

  return (
    <div className="space-y-3">
      {/* Group By */}
      <div className="space-y-1">
        <h3 className="text-sm font-medium text-gray-700">Group By</h3>
        <div className="flex flex-wrap gap-1">
          {availableFields.map((f) => (
            <button
              key={f}
              type="button"
              onClick={() => toggleGroupBy(f)}
              className={`rounded-full border px-2 py-0.5 text-xs ${
                groupBy.includes(f)
                  ? 'border-blue-300 bg-blue-100 text-blue-800'
                  : 'border-gray-200 bg-white text-gray-600 hover:bg-gray-50'
              }`}
            >
              {f}
            </button>
          ))}
        </div>
      </div>

      {/* Aggregations */}
      <div className="space-y-2">
        <div className="flex items-center justify-between">
          <h3 className="text-sm font-medium text-gray-700">Metrics</h3>
          <button
            type="button"
            onClick={addAggregation}
            className="text-xs text-blue-600 hover:text-blue-800"
            data-testid="qb-add-aggregation"
          >
            + Add Metric
          </button>
        </div>
        {aggregations.map((agg, index) => (
          <div key={index} className="flex items-center gap-1.5">
            <select
              value={agg.function}
              onChange={(e) =>
                updateAgg(index, { function: e.target.value as AggConfig['function'] })
              }
              className="w-20 rounded-md border border-gray-300 px-2 py-1 text-sm"
            >
              {AGG_FUNCTIONS.map((fn) => (
                <option key={fn} value={fn}>
                  {fn}
                </option>
              ))}
            </select>
            <select
              value={agg.fieldCode}
              onChange={(e) => updateAgg(index, { fieldCode: e.target.value })}
              className="flex-1 rounded-md border border-gray-300 px-2 py-1 text-sm"
            >
              {availableFields.map((f) => (
                <option key={f} value={f}>
                  {f}
                </option>
              ))}
            </select>
            <input
              type="text"
              value={agg.alias || ''}
              onChange={(e) => updateAgg(index, { alias: e.target.value })}
              placeholder="Alias"
              className="w-24 rounded-md border border-gray-300 px-2 py-1 text-sm"
            />
            <button
              type="button"
              onClick={() => removeAgg(index)}
              className="p-1 text-red-500 hover:text-red-700"
            >
              <svg className="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  strokeWidth={2}
                  d="M6 18L18 6M6 6l12 12"
                />
              </svg>
            </button>
          </div>
        ))}
      </div>
    </div>
  );
};
