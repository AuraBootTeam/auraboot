import React from 'react';
import { OPERATOR_INFO, type FilterCondition, type FilterOperator } from './types';

interface FilterConditionBuilderProps {
  conditions: FilterCondition[];
  logic: 'and' | 'OR';
  onLogicChange: (logic: 'and' | 'OR') => void;
  onAdd: () => void;
  onRemove: (condId: string) => void;
  onUpdate: (condId: string, updates: Partial<FilterCondition>) => void;
  fieldOptions?: { code: string; label: string }[];
  readonly?: boolean;
}

/**
 * Filter Condition Builder - visual condition list with AND/OR logic.
 */
export const FilterConditionBuilder: React.FC<FilterConditionBuilderProps> = ({
  conditions,
  logic,
  onLogicChange,
  onAdd,
  onRemove,
  onUpdate,
  fieldOptions = [],
  readonly = false,
}) => {
  return (
    <div className="space-y-2">
      {/* Logic toggle */}
      {conditions.length > 1 && (
        <div className="mb-2 flex items-center gap-2">
          <span className="text-xs text-gray-500">条件逻辑:</span>
          <div className="flex items-center rounded bg-gray-100 p-0.5">
            <button
              onClick={() => onLogicChange('and')}
              className={`rounded px-2 py-0.5 text-xs ${logic === 'and' ? 'bg-white text-gray-700 shadow-sm' : 'text-gray-500'}`}
              disabled={readonly}
            >
              且 (AND)
            </button>
            <button
              onClick={() => onLogicChange('OR')}
              className={`rounded px-2 py-0.5 text-xs ${logic === 'OR' ? 'bg-white text-gray-700 shadow-sm' : 'text-gray-500'}`}
              disabled={readonly}
            >
              或 (OR)
            </button>
          </div>
        </div>
      )}

      {/* Condition list */}
      {conditions.map((cond, idx) => (
        <div key={cond.id} className="flex items-center gap-1.5">
          {/* Logic label between conditions */}
          {idx > 0 && (
            <span className="w-6 shrink-0 text-center text-[10px] text-gray-400">{logic}</span>
          )}
          {idx === 0 && conditions.length > 1 && <span className="w-6 shrink-0" />}

          {/* Field selector */}
          <select
            value={cond.fieldCode}
            onChange={(e) => onUpdate(cond.id, { fieldCode: e.target.value })}
            className="min-w-0 flex-1 rounded border border-gray-200 px-2 py-1 text-xs focus:ring-1 focus:ring-blue-300 focus:outline-none"
            disabled={readonly}
          >
            <option value="">选择字段</option>
            {fieldOptions.map((f) => (
              <option key={f.code} value={f.code}>
                {f.label}
              </option>
            ))}
          </select>

          {/* Operator */}
          <select
            value={cond.operator}
            onChange={(e) => onUpdate(cond.id, { operator: e.target.value as FilterOperator })}
            className="w-20 rounded border border-gray-200 px-1.5 py-1 text-xs focus:ring-1 focus:ring-blue-300 focus:outline-none"
            disabled={readonly}
          >
            {Object.entries(OPERATOR_INFO).map(([op, info]) => (
              <option key={op} value={op}>
                {info.label}
              </option>
            ))}
          </select>

          {/* Value input */}
          {OPERATOR_INFO[cond.operator]?.valueCount > 0 && (
            <input
              type="text"
              value={cond.value ?? ''}
              onChange={(e) => onUpdate(cond.id, { value: e.target.value })}
              placeholder="值"
              className="min-w-0 flex-1 rounded border border-gray-200 px-2 py-1 text-xs focus:ring-1 focus:ring-blue-300 focus:outline-none"
              disabled={readonly}
            />
          )}

          {/* Remove button */}
          {!readonly && (
            <button
              onClick={() => onRemove(cond.id)}
              className="shrink-0 p-0.5 text-gray-400 hover:text-red-500"
              title="删除条件"
            >
              <svg className="h-3.5 w-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  strokeWidth={2}
                  d="M6 18L18 6M6 6l12 12"
                />
              </svg>
            </button>
          )}
        </div>
      ))}

      {/* Add button */}
      {!readonly && (
        <button
          onClick={onAdd}
          className="w-full rounded border border-dashed border-gray-300 py-1.5 text-xs text-gray-400 transition-colors hover:border-blue-300 hover:text-blue-500"
        >
          + 添加条件
        </button>
      )}

      {conditions.length === 0 && (
        <div className="py-3 text-center text-xs text-gray-400">暂无过滤条件</div>
      )}
    </div>
  );
};
