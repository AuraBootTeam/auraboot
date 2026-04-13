/**
 * ViewFilterPanel Component
 *
 * Configuration panel for adding/editing filter conditions on saved views.
 * Supports both static values and expression-based filters via FormulaEditor.
 */

import React, { useCallback } from 'react';
import type { ViewConfig, ViewFilterConfig } from '~/framework/smart/types/savedView';
import type { FieldOption } from './KanbanConfigPanel';
import { FormulaEditor } from '~/framework/smart/components/formula/FormulaEditor';
import { cn } from '~/utils/cn';

/**
 * Props for ViewFilterPanel component
 */
export interface ViewFilterPanelProps {
  /** Current view configuration */
  viewConfig: ViewConfig;
  /** Callback when configuration changes */
  onChange: (config: ViewConfig) => void;
  /** Available model fields for filter selection */
  fields: FieldOption[];
  /** Custom CSS class */
  className?: string;
}

const OPERATOR_OPTIONS = [
  { value: 'eq', label: '=' },
  { value: 'ne', label: '!=' },
  { value: 'gt', label: '>' },
  { value: 'gte', label: '>=' },
  { value: 'lt', label: '<' },
  { value: 'lte', label: '<=' },
  { value: 'like', label: 'Contains' },
  { value: 'in', label: 'In' },
  { value: 'isNull', label: 'Is Null' },
  { value: 'isNotNull', label: 'Is Not Null' },
] as const;

const NO_VALUE_OPERATORS = ['isNull', 'isNotNull'];

/**
 * ViewFilterPanel - Filter condition builder for saved views
 */
export const ViewFilterPanel: React.FC<ViewFilterPanelProps> = ({
  viewConfig,
  onChange,
  fields,
  className,
}) => {
  const filters = viewConfig.filters || [];

  const updateFilters = useCallback(
    (newFilters: ViewFilterConfig[]) => {
      onChange({ ...viewConfig, filters: newFilters });
    },
    [viewConfig, onChange],
  );

  const handleAddFilter = useCallback(() => {
    const newFilter: ViewFilterConfig = {
      fieldCode: '',
      operator: 'eq',
      value: '',
      logic: filters.length > 0 ? 'and' : undefined,
    };
    updateFilters([...filters, newFilter]);
  }, [filters, updateFilters]);

  const handleRemoveFilter = useCallback(
    (index: number) => {
      const updated = filters.filter((_, i) => i !== index);
      // Remove logic from first filter
      if (updated.length > 0 && updated[0].logic) {
        updated[0] = { ...updated[0], logic: undefined };
      }
      updateFilters(updated);
    },
    [filters, updateFilters],
  );

  const handleUpdateFilter = useCallback(
    (index: number, partial: Partial<ViewFilterConfig>) => {
      const updated = [...filters];
      updated[index] = { ...updated[index], ...partial };
      updateFilters(updated);
    },
    [filters, updateFilters],
  );

  const selectClassName =
    'rounded-md border border-gray-300 px-2 py-1.5 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-blue-500';

  return (
    <div className={cn('space-y-3', className)}>
      {filters.length === 0 ? (
        <p className="py-2 text-center text-sm text-gray-500">No filters configured</p>
      ) : (
        filters.map((filter, index) => (
          <div key={index} className="space-y-2 rounded-lg border border-gray-200 p-3">
            {/* Logic operator (for 2nd+ filters) */}
            {index > 0 && (
              <div className="mb-1 flex items-center gap-2">
                <select
                  value={filter.logic || 'and'}
                  onChange={(e) =>
                    handleUpdateFilter(index, { logic: e.target.value as 'and' | 'OR' })
                  }
                  className={cn(selectClassName, 'w-20')}
                >
                  <option value="and">AND</option>
                  <option value="OR">OR</option>
                </select>
              </div>
            )}

            {/* Field + Operator row */}
            <div className="flex items-center gap-2">
              <select
                value={filter.fieldCode}
                onChange={(e) => handleUpdateFilter(index, { fieldCode: e.target.value })}
                className={cn(selectClassName, 'flex-1')}
              >
                <option value="">Select field...</option>
                {fields.map((f) => (
                  <option key={f.code} value={f.code}>
                    {f.name}
                  </option>
                ))}
              </select>

              <select
                value={filter.operator}
                onChange={(e) =>
                  handleUpdateFilter(index, {
                    operator: e.target.value as ViewFilterConfig['operator'],
                  })
                }
                className={cn(selectClassName, 'w-28')}
              >
                {OPERATOR_OPTIONS.map((op) => (
                  <option key={op.value} value={op.value}>
                    {op.label}
                  </option>
                ))}
              </select>

              <button
                type="button"
                onClick={() => handleRemoveFilter(index)}
                className="shrink-0 p-1.5 text-gray-400 hover:text-red-500"
                title="Remove filter"
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

            {/* Value input (unless operator needs no value) */}
            {!NO_VALUE_OPERATORS.includes(filter.operator) && (
              <div>
                {/* Value mode toggle */}
                <div className="mb-1 flex items-center justify-between">
                  <span className="text-xs text-gray-500">Value</span>
                  <div className="flex overflow-hidden rounded border border-gray-200">
                    <button
                      type="button"
                      onClick={() =>
                        handleUpdateFilter(index, { isExpression: false, expression: undefined })
                      }
                      className={cn(
                        'px-2 py-0.5 text-xs transition-colors',
                        !filter.isExpression
                          ? 'bg-blue-500 text-white'
                          : 'bg-white text-gray-500 hover:bg-gray-50',
                      )}
                    >
                      Static
                    </button>
                    <button
                      type="button"
                      onClick={() => handleUpdateFilter(index, { isExpression: true })}
                      className={cn(
                        'border-l px-2 py-0.5 text-xs transition-colors',
                        filter.isExpression
                          ? 'bg-blue-500 text-white'
                          : 'bg-white text-gray-500 hover:bg-gray-50',
                      )}
                    >
                      Expression
                    </button>
                  </div>
                </div>

                {filter.isExpression ? (
                  <FormulaEditor
                    value={filter.expression || ''}
                    onChange={(val) => handleUpdateFilter(index, { expression: val })}
                    placeholder="e.g. #currentUser or #NOW()"
                    fields={fields.map((f) => ({ code: f.code, name: f.name }))}
                  />
                ) : (
                  <input
                    type="text"
                    value={String(filter.value ?? '')}
                    onChange={(e) => handleUpdateFilter(index, { value: e.target.value })}
                    placeholder="Filter value"
                    className="w-full rounded-md border border-gray-300 px-2.5 py-1.5 text-sm focus:ring-2 focus:ring-blue-500 focus:outline-none"
                  />
                )}
              </div>
            )}
          </div>
        ))
      )}

      <button
        type="button"
        onClick={handleAddFilter}
        className="w-full py-1.5 text-sm text-blue-600 hover:text-blue-700"
      >
        + Add Filter
      </button>
    </div>
  );
};

export default ViewFilterPanel;
