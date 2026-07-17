/**
 * SortEditor
 *
 * Configure the result ordering (`orderBy`) for an aggregate data source. The
 * backend has always supported ordering, but with no UI and no field on the data
 * source the `limit` on a leaderboard was "any N rows", never top-N. Ordering by a
 * metric alias fixes that.
 *
 * A sort key is either a dimension (grouped column) or a metric alias — exactly the
 * columns the query returns — so the dropdown is built from both.
 */

import React from 'react';
import type { SortCondition } from './types';

export interface SortOption {
  /** The column/alias to sort by. */
  value: string;
  /** Human label. */
  label: string;
}

export interface SortEditorProps {
  value: SortCondition[];
  onChange: (sorts: SortCondition[]) => void;
  /** Selectable sort keys — dimensions and metric aliases the query exposes. */
  options: SortOption[];
  label?: string;
}

export const SortEditor: React.FC<SortEditorProps> = ({ value, onChange, options, label }) => {
  const add = () => {
    const first = options[0]?.value;
    if (!first) return;
    onChange([...value, { field: first, order: 'desc' }]);
  };

  const update = (index: number, patch: Partial<SortCondition>) => {
    onChange(value.map((s, i) => (i === index ? { ...s, ...patch } : s)));
  };

  const remove = (index: number) => {
    onChange(value.filter((_, i) => i !== index));
  };

  return (
    <div data-testid="sort-editor">
      {label && <label className="mb-1 block text-sm font-medium text-gray-700">{label}</label>}

      {options.length === 0 ? (
        <p className="text-sm text-gray-400">先选择维度或指标</p>
      ) : (
        <>
          {value.map((sort, index) => (
            <div key={index} className="mb-2 flex items-center gap-2" data-testid="sort-row">
              <select
                data-testid="sort-field"
                value={sort.field}
                onChange={(e) => update(index, { field: e.target.value })}
                className="flex-1 rounded-md border border-gray-300 px-2 py-1.5 text-sm focus:ring-2 focus:ring-blue-500 focus:outline-none"
              >
                {options.map((o) => (
                  <option key={o.value} value={o.value}>
                    {o.label}
                  </option>
                ))}
              </select>
              <select
                data-testid="sort-direction"
                value={sort.order}
                onChange={(e) => update(index, { order: e.target.value as 'asc' | 'desc' })}
                className="rounded-md border border-gray-300 px-2 py-1.5 text-sm focus:ring-2 focus:ring-blue-500 focus:outline-none"
              >
                <option value="desc">降序</option>
                <option value="asc">升序</option>
              </select>
              <button
                type="button"
                onClick={() => remove(index)}
                className="px-2 py-1 text-sm text-gray-400 hover:text-red-500"
                aria-label="移除排序"
              >
                ✕
              </button>
            </div>
          ))}
          <button
            type="button"
            data-testid="sort-add"
            onClick={add}
            className="text-sm text-blue-600 hover:text-blue-700"
          >
            + 添加排序
          </button>
        </>
      )}
    </div>
  );
};

export default SortEditor;
