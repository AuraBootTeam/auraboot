/**
 * AggregationConfig — GROUP BY chips + aggregation rows. Optional step.
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
    onGroupByChange(groupBy.includes(field) ? groupBy.filter((f) => f !== field) : [...groupBy, field]);
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

  const isEmpty = groupBy.length === 0 && aggregations.length === 0;
  const summary = isEmpty
    ? 'empty'
    : `${groupBy.length} group${groupBy.length === 1 ? '' : 's'} · ${aggregations.length} agg`;

  return (
    <section data-testid="qb-step-aggregate" className="rounded-xl border border-slate-200 bg-slate-50/60 p-5 shadow-sm">
      <header className="mb-3 flex items-center justify-between">
        <div className="flex items-center gap-2">
          <span className="inline-flex h-6 w-6 items-center justify-center rounded-full bg-slate-200 text-xs font-semibold text-slate-600">
            3
          </span>
          <h3 className="text-sm font-semibold text-slate-700">Group &amp; Aggregate</h3>
          <span className="rounded-full bg-slate-200 px-2 py-0.5 text-[10px] font-medium tracking-wide text-slate-600 uppercase">
            optional
          </span>
        </div>
        <span className="text-xs text-slate-500">{summary}</span>
      </header>
      <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
        <div>
          <div className="mb-2 text-xs font-medium tracking-wide text-slate-500 uppercase">Group by</div>
          {availableFields.length === 0 ? (
            <p className="text-xs text-slate-400">Select a model first</p>
          ) : (
            <div className="flex flex-wrap gap-1.5">
              {availableFields.map((f) => {
                const active = groupBy.includes(f);
                return (
                  <button
                    key={f}
                    type="button"
                    onClick={() => toggleGroupBy(f)}
                    className={`rounded-full border px-2.5 py-0.5 text-xs transition-colors ${
                      active
                        ? 'border-blue-500 bg-blue-50 text-blue-700'
                        : 'border-slate-200 bg-white text-slate-600 hover:border-blue-400'
                    }`}
                  >
                    {f}
                  </button>
                );
              })}
            </div>
          )}
        </div>
        <div>
          <div className="mb-2 flex items-center justify-between">
            <span className="text-xs font-medium tracking-wide text-slate-500 uppercase">Aggregations</span>
            <button
              type="button"
              onClick={addAggregation}
              data-testid="qb-add-aggregation"
              className="text-xs font-medium text-blue-600 hover:text-blue-700"
            >
              + Add
            </button>
          </div>
          {aggregations.length === 0 && <p className="text-xs text-slate-400">No aggregations</p>}
          <div className="space-y-1.5">
            {aggregations.map((agg, index) => (
              <div key={index} className="flex items-center gap-1.5">
                <select
                  value={agg.function}
                  onChange={(e) => updateAgg(index, { function: e.target.value as AggConfig['function'] })}
                  className="w-20 rounded-lg border border-slate-200 bg-white px-2 py-1 text-xs focus:border-blue-500 focus:outline-none"
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
                  className="flex-1 rounded-lg border border-slate-200 bg-white px-2 py-1 text-xs focus:border-blue-500 focus:outline-none"
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
                  placeholder="alias"
                  className="w-24 rounded-lg border border-slate-200 bg-white px-2 py-1 text-xs placeholder:text-slate-400 focus:border-blue-500 focus:outline-none"
                />
                <button
                  type="button"
                  onClick={() => removeAgg(index)}
                  aria-label="Remove aggregation"
                  className="rounded-md p-1 text-slate-400 hover:bg-rose-50 hover:text-rose-600"
                >
                  <svg className="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M6 18L18 6M6 6l12 12" />
                  </svg>
                </button>
              </div>
            ))}
          </div>
        </div>
      </div>
    </section>
  );
};
