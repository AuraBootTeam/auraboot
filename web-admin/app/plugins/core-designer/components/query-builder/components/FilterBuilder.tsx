/**
 * FilterBuilder — Token-row WHERE conditions joined by AND.
 */

import type { FilterCondition } from '../services/queryBuilderService';

const OPERATORS = ['EQ', 'NEQ', 'GT', 'GTE', 'LT', 'LTE', 'LIKE', 'IN', 'IS_NULL', 'IS_NOT_NULL'];
const OPERATOR_LABELS: Record<string, string> = {
  EQ: '=',
  NEQ: '≠',
  GT: '>',
  GTE: '≥',
  LT: '<',
  LTE: '≤',
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

export const FilterBuilder: React.FC<FilterBuilderProps> = ({ filters, availableFields, onChange }) => {
  const addFilter = () => {
    onChange([
      ...filters,
      { fieldName: availableFields[0] || '', operator: 'EQ', value: '' },
    ]);
  };

  const updateFilter = (index: number, patch: Partial<FilterCondition>) => {
    onChange(filters.map((f, i) => (i === index ? { ...f, ...patch } : f)));
  };

  const removeFilter = (index: number) => {
    onChange(filters.filter((_, i) => i !== index));
  };

  return (
    <section data-testid="qb-step-filters" className="rounded-xl border border-slate-200 bg-white p-5 shadow-sm">
      <header className="mb-3 flex items-center justify-between">
        <div className="flex items-center gap-2">
          <span className="inline-flex h-6 w-6 items-center justify-center rounded-full bg-blue-50 text-xs font-semibold text-blue-700 ring-1 ring-blue-200">
            2
          </span>
          <h3 className="text-sm font-semibold text-slate-700">Filters</h3>
        </div>
        <span className="text-xs text-slate-500">{filters.length} conditions</span>
      </header>
      {filters.length === 0 && (
        <p className="mb-3 text-xs text-slate-400">No filters. Results will include all rows up to the limit.</p>
      )}
      <div className="space-y-2">
        {filters.map((filter, index) => {
          const showValue = !['IS_NULL', 'IS_NOT_NULL'].includes(filter.operator);
          return (
            <div key={index} data-testid={`qb-filter-row-${index}`} className="flex items-center gap-2">
              {index > 0 && <span className="text-[11px] font-semibold tracking-wider text-slate-400">AND</span>}
              <select
                data-role="field"
                value={filter.fieldName}
                onChange={(e) => updateFilter(index, { fieldName: e.target.value })}
                className="flex-1 rounded-lg border border-slate-200 bg-white px-2.5 py-1.5 text-sm focus:border-blue-500 focus:ring-2 focus:ring-blue-100 focus:outline-none"
              >
                {availableFields.map((f) => (
                  <option key={f} value={f}>
                    {f}
                  </option>
                ))}
              </select>
              <select
                data-role="op"
                value={filter.operator}
                onChange={(e) => updateFilter(index, { operator: e.target.value })}
                className="w-28 rounded-lg border border-slate-200 bg-white px-2.5 py-1.5 text-sm focus:border-blue-500 focus:ring-2 focus:ring-blue-100 focus:outline-none"
              >
                {OPERATORS.map((op) => (
                  <option key={op} value={op}>
                    {OPERATOR_LABELS[op]}
                  </option>
                ))}
              </select>
              {showValue && (
                <input
                  data-role="value"
                  type="text"
                  value={filter.value}
                  onChange={(e) => updateFilter(index, { value: e.target.value })}
                  placeholder="value"
                  className="flex-1 rounded-lg border border-slate-200 bg-white px-2.5 py-1.5 text-sm placeholder:text-slate-400 focus:border-blue-500 focus:ring-2 focus:ring-blue-100 focus:outline-none"
                />
              )}
              <button
                type="button"
                onClick={() => removeFilter(index)}
                aria-label="Remove filter"
                className="rounded-md p-1.5 text-slate-400 hover:bg-rose-50 hover:text-rose-600"
              >
                <svg className="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M6 18L18 6M6 6l12 12" />
                </svg>
              </button>
            </div>
          );
        })}
      </div>
      <button
        type="button"
        onClick={addFilter}
        data-testid="qb-add-filter"
        className="mt-3 inline-flex items-center gap-1 rounded-lg border border-dashed border-slate-300 px-3 py-1.5 text-xs font-medium text-slate-600 hover:border-blue-400 hover:text-blue-700"
      >
        + Add filter
      </button>
    </section>
  );
};
