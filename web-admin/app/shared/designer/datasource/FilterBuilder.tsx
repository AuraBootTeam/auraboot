/**
 * Filter Builder — shared component for building filter conditions.
 * Used by Dashboard DataSourceConfig and Report block editors.
 */

import React from 'react';
import type { FilterCondition, FieldOption } from './types';
import { FILTER_OPERATORS } from './types';
import { Plus, X } from 'lucide-react';
import { useSmartText } from '~/utils/i18n';

const operatorLabels = {
  eq: { zh: '等于', en: 'Equals' },
  ne: { zh: '不等于', en: 'Not equals' },
  gt: { zh: '大于', en: 'Greater than' },
  gte: { zh: '大于等于', en: 'Greater or equal' },
  lt: { zh: '小于', en: 'Less than' },
  lte: { zh: '小于等于', en: 'Less or equal' },
  like: { zh: '包含', en: 'Contains' },
  in: { zh: '属于列表', en: 'In list' },
  isNull: { zh: '为空', en: 'Is empty' },
  isNotNull: { zh: '不为空', en: 'Is not empty' },
};

export interface FilterBuilderProps {
  value: FilterCondition[];
  onChange: (filters: FilterCondition[]) => void;
  /** Available fields for the field dropdown. If not provided, uses free-text input. */
  fields?: FieldOption[];
  label?: string;
  className?: string;
}

export const FilterBuilder: React.FC<FilterBuilderProps> = ({
  value,
  onChange,
  fields,
  label,
  className,
}) => {
  const text = useSmartText();
  const heading = label ?? text({ zh: '筛选条件', en: 'Filters' });
  const fieldLabel = text({ zh: '字段', en: 'Field' });
  const valueLabel = text({ zh: '值', en: 'Value' });
  const addFilter = () => {
    onChange([...value, { field: '', operator: 'eq', value: '' }]);
  };

  const removeFilter = (index: number) => {
    onChange(value.filter((_, i) => i !== index));
  };

  const updateFilter = (index: number, key: keyof FilterCondition, val: string) => {
    const updated = [...value];
    updated[index] = { ...updated[index], [key]: val };
    onChange(updated);
  };

  return (
    <div className={className} data-testid="designer-filter-builder">
      {heading && (
        <div className="mb-2 flex items-center justify-between">
          <span className="text-sm font-medium text-gray-700">{heading}</span>
          <button
            type="button"
            onClick={addFilter}
            className="flex items-center gap-1 text-xs text-blue-600 hover:text-blue-700"
          >
            <Plus className="h-3 w-3" />
            {text({ zh: '添加条件', en: 'Add filter' })}
          </button>
        </div>
      )}

      {value.length === 0 && (
        <p className="text-xs text-gray-400">
          {text({ zh: '未设置筛选条件', en: 'No filters configured' })}
        </p>
      )}

      <div className="space-y-2">
        {value.map((filter, index) => (
          <div
            key={index}
            className="grid grid-cols-[minmax(0,1fr)_minmax(0,1fr)_auto] items-center gap-2 rounded-md border border-gray-200 p-2"
          >
            {/* Field */}
            {fields ? (
              <select
                aria-label={fieldLabel}
                value={filter.field}
                onChange={(e) => updateFilter(index, 'field', e.target.value)}
                className="col-span-3 min-w-0 rounded-md border border-gray-300 px-2 py-1.5 text-xs focus:ring-1 focus:ring-blue-500 focus:outline-none"
              >
                <option value="">{fieldLabel}</option>
                {fields.map((f) => (
                  <option key={f.code} value={f.code}>
                    {f.name}
                  </option>
                ))}
              </select>
            ) : (
              <input
                aria-label={fieldLabel}
                type="text"
                value={filter.field}
                onChange={(e) => updateFilter(index, 'field', e.target.value)}
                placeholder={fieldLabel}
                className="col-span-3 min-w-0 rounded-md border border-gray-300 px-2 py-1.5 text-xs focus:ring-1 focus:ring-blue-500 focus:outline-none"
              />
            )}

            {/* Operator */}
            <select
              aria-label={text({ zh: '运算符', en: 'Operator' })}
              value={filter.operator}
              onChange={(e) => updateFilter(index, 'operator', e.target.value)}
              className="min-w-0 rounded-md border border-gray-300 px-2 py-1.5 text-xs focus:ring-1 focus:ring-blue-500 focus:outline-none"
            >
              {FILTER_OPERATORS.map((op) => (
                <option key={op.value} value={op.value}>
                  {text(operatorLabels[op.value])}
                </option>
              ))}
            </select>

            {/* Value (hidden for isNull/isNotNull) */}
            {filter.operator !== 'isNull' && filter.operator !== 'isNotNull' && (
              <input
                aria-label={valueLabel}
                type="text"
                value={filter.value}
                onChange={(e) => updateFilter(index, 'value', e.target.value)}
                placeholder={valueLabel}
                className="min-w-0 rounded-md border border-gray-300 px-2 py-1.5 text-xs focus:ring-1 focus:ring-blue-500 focus:outline-none"
              />
            )}

            {/* Remove */}
            <button
              type="button"
              aria-label={text({ zh: '移除条件', en: 'Remove filter' })}
              onClick={() => removeFilter(index)}
              className="col-start-3 row-start-2 rounded p-1 text-gray-400 hover:bg-gray-100 hover:text-red-500"
            >
              <X className="h-3.5 w-3.5" />
            </button>
          </div>
        ))}
      </div>
    </div>
  );
};
