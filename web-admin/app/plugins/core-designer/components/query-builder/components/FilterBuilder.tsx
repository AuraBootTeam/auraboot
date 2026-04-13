/**
 * FilterBuilder — Add WHERE conditions to the query
 */

import type { FilterCondition } from '../services/queryBuilderService';

const OPERATORS = ['EQ', 'NEQ', 'GT', 'GTE', 'LT', 'LTE', 'LIKE', 'IN', 'IS_NULL', 'IS_NOT_NULL'];
const OPERATOR_LABELS: Record<string, string> = {
  EQ: '=',
  NEQ: '!=',
  GT: '>',
  GTE: '>=',
  LT: '<',
  LTE: '<=',
  LIKE: 'contains',
  IN: 'in',
  IS_NULL: 'is null',
  IS_NOT_NULL: 'is not null',
};

interface FilterBuilderProps {
  filters: FilterCondition[];
  availableFields: string[];
  onChange: (filters: FilterCondition[]) => void;
}

export const FilterBuilder: React.FC<FilterBuilderProps> = ({
  filters,
  availableFields,
  onChange,
}) => {
  const addFilter = () => {
    onChange([...filters, { fieldName: availableFields[0] || '', operator: 'EQ', value: '' }]);
  };

  const updateFilter = (index: number, patch: Partial<FilterCondition>) => {
    const updated = filters.map((f, i) => (i === index ? { ...f, ...patch } : f));
    onChange(updated);
  };

  const removeFilter = (index: number) => {
    onChange(filters.filter((_, i) => i !== index));
  };

  return (
    <div className="space-y-2">
      <div className="flex items-center justify-between">
        <h3 className="text-sm font-medium text-gray-700">Filters</h3>
        <button
          type="button"
          onClick={addFilter}
          className="text-xs text-blue-600 hover:text-blue-800"
          data-testid="qb-add-filter"
        >
          + Add Filter
        </button>
      </div>
      {filters.map((filter, index) => (
        <div key={index} className="flex items-center gap-1.5">
          <select
            value={filter.fieldName}
            onChange={(e) => updateFilter(index, { fieldName: e.target.value })}
            className="flex-1 rounded-md border border-gray-300 px-2 py-1 text-sm"
          >
            {availableFields.map((f) => (
              <option key={f} value={f}>
                {f}
              </option>
            ))}
          </select>
          <select
            value={filter.operator}
            onChange={(e) => updateFilter(index, { operator: e.target.value })}
            className="w-24 rounded-md border border-gray-300 px-2 py-1 text-sm"
          >
            {OPERATORS.map((op) => (
              <option key={op} value={op}>
                {OPERATOR_LABELS[op]}
              </option>
            ))}
          </select>
          {!['IS_NULL', 'IS_NOT_NULL'].includes(filter.operator) && (
            <input
              type="text"
              value={filter.value}
              onChange={(e) => updateFilter(index, { value: e.target.value })}
              placeholder="Value"
              className="flex-1 rounded-md border border-gray-300 px-2 py-1 text-sm"
            />
          )}
          <button
            type="button"
            onClick={() => removeFilter(index)}
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
      {filters.length === 0 && <p className="text-xs text-gray-400">No filters applied</p>}
    </div>
  );
};
