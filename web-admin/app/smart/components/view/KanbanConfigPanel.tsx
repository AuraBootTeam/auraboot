/**
 * KanbanConfigPanel Component
 *
 * Configuration panel for creating/editing KANBAN view settings.
 * Allows selecting groupByField, titleField, card fields, and display options.
 */

import React, { useCallback } from 'react';
import type {
  ViewConfig,
  KanbanCardFieldConfig,
  KanbanAggregationConfig,
} from '~/smart/types/savedView';
import { cn } from '~/utils/cn';

/**
 * Simplified model field definition for the config panel
 */
export interface FieldOption {
  code: string;
  name: string;
  dataType: string;
}

/**
 * Props for KanbanConfigPanel component
 */
export interface KanbanConfigPanelProps {
  /** Current view configuration */
  viewConfig: ViewConfig;
  /** Callback when configuration changes */
  onChange: (config: ViewConfig) => void;
  /** Available model fields for selection */
  fields: FieldOption[];
  /** Custom CSS class */
  className?: string;
}

/**
 * KanbanConfigPanel - Configuration UI for kanban view settings
 *
 * @example
 * <KanbanConfigPanel
 *   viewConfig={config}
 *   onChange={setConfig}
 *   fields={modelFields}
 * />
 */
export const KanbanConfigPanel: React.FC<KanbanConfigPanelProps> = ({
  viewConfig,
  onChange,
  fields,
  className,
}) => {
  const updateConfig = useCallback(
    (partial: Partial<ViewConfig>) => {
      onChange({ ...viewConfig, ...partial });
    },
    [viewConfig, onChange],
  );

  const handleAddCardField = useCallback(() => {
    const existing = viewConfig.cardFields || [];
    const newField: KanbanCardFieldConfig = { field: '', type: 'text' };
    updateConfig({ cardFields: [...existing, newField] });
  }, [viewConfig.cardFields, updateConfig]);

  const handleRemoveCardField = useCallback(
    (index: number) => {
      const updated = [...(viewConfig.cardFields || [])];
      updated.splice(index, 1);
      updateConfig({ cardFields: updated });
    },
    [viewConfig.cardFields, updateConfig],
  );

  const handleUpdateCardField = useCallback(
    (index: number, partial: Partial<KanbanCardFieldConfig>) => {
      const updated = [...(viewConfig.cardFields || [])];
      updated[index] = { ...updated[index], ...partial };
      updateConfig({ cardFields: updated });
    },
    [viewConfig.cardFields, updateConfig],
  );

  const handleAddAggregation = useCallback(() => {
    const existing = viewConfig.kanbanAggregations || [];
    const newAgg: KanbanAggregationConfig = {
      field: '',
      function: 'count',
    };
    updateConfig({ kanbanAggregations: [...existing, newAgg] });
  }, [viewConfig.kanbanAggregations, updateConfig]);

  const handleRemoveAggregation = useCallback(
    (index: number) => {
      const updated = [...(viewConfig.kanbanAggregations || [])];
      updated.splice(index, 1);
      updateConfig({ kanbanAggregations: updated });
    },
    [viewConfig.kanbanAggregations, updateConfig],
  );

  const handleUpdateAggregation = useCallback(
    (index: number, partial: Partial<KanbanAggregationConfig>) => {
      const updated = [...(viewConfig.kanbanAggregations || [])];
      updated[index] = { ...updated[index], ...partial };
      updateConfig({ kanbanAggregations: updated });
    },
    [viewConfig.kanbanAggregations, updateConfig],
  );

  const selectClassName =
    'w-full rounded-md border border-gray-300 px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-blue-500';

  return (
    <div className={cn('space-y-5', className)}>
      {/* Group By Field (required) */}
      <div>
        <label className="mb-1 block text-sm font-medium text-gray-700">
          Group By Field <span className="text-red-500">*</span>
        </label>
        <select
          value={viewConfig.groupByField || ''}
          onChange={(e) => updateConfig({ groupByField: e.target.value })}
          className={selectClassName}
        >
          <option value="">Select field...</option>
          {fields.map((f) => (
            <option key={f.code} value={f.code}>
              {f.name} ({f.dataType})
            </option>
          ))}
        </select>
        <p className="mt-1 text-xs text-gray-500">
          Cards will be grouped into columns by this field value.
        </p>
      </div>

      {/* Title Field (required) */}
      <div>
        <label className="mb-1 block text-sm font-medium text-gray-700">
          Title Field <span className="text-red-500">*</span>
        </label>
        <select
          value={viewConfig.titleField || ''}
          onChange={(e) => updateConfig({ titleField: e.target.value })}
          className={selectClassName}
        >
          <option value="">Select field...</option>
          {fields.map((f) => (
            <option key={f.code} value={f.code}>
              {f.name}
            </option>
          ))}
        </select>
      </div>

      {/* Description Field (optional) */}
      <div>
        <label className="mb-1 block text-sm font-medium text-gray-700">Description Field</label>
        <select
          value={viewConfig.descriptionField || ''}
          onChange={(e) =>
            updateConfig({
              descriptionField: e.target.value || undefined,
            })
          }
          className={selectClassName}
        >
          <option value="">None</option>
          {fields.map((f) => (
            <option key={f.code} value={f.code}>
              {f.name}
            </option>
          ))}
        </select>
      </div>

      {/* Card Fields */}
      <div>
        <div className="mb-2 flex items-center justify-between">
          <label className="text-sm font-medium text-gray-700">Card Fields</label>
          <button
            type="button"
            onClick={handleAddCardField}
            className="text-sm text-blue-600 hover:text-blue-700"
          >
            + Add Field
          </button>
        </div>
        {(viewConfig.cardFields || []).map((cf, index) => (
          <div key={index} className="mb-2 flex items-center gap-2">
            <select
              value={cf.field}
              onChange={(e) => handleUpdateCardField(index, { field: e.target.value })}
              className="flex-1 rounded-md border border-gray-300 px-2 py-1.5 text-sm"
            >
              <option value="">Select field...</option>
              {fields.map((f) => (
                <option key={f.code} value={f.code}>
                  {f.name}
                </option>
              ))}
            </select>
            <select
              value={cf.type || 'text'}
              onChange={(e) => handleUpdateCardField(index, { type: e.target.value })}
              className="w-24 rounded-md border border-gray-300 px-2 py-1.5 text-sm"
            >
              <option value="text">Text</option>
              <option value="number">Number</option>
              <option value="date">Date</option>
              <option value="tag">Tag</option>
              <option value="avatar">Avatar</option>
            </select>
            <button
              type="button"
              onClick={() => handleRemoveCardField(index)}
              className="p-1.5 text-gray-400 hover:text-red-500"
              title="Remove"
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

      {/* Aggregations */}
      <div>
        <div className="mb-2 flex items-center justify-between">
          <label className="text-sm font-medium text-gray-700">Column Aggregations</label>
          <button
            type="button"
            onClick={handleAddAggregation}
            className="text-sm text-blue-600 hover:text-blue-700"
          >
            + Add Aggregation
          </button>
        </div>
        {(viewConfig.kanbanAggregations || []).map((agg, index) => (
          <div key={index} className="mb-2 flex items-center gap-2">
            <select
              value={agg.field}
              onChange={(e) => handleUpdateAggregation(index, { field: e.target.value })}
              className="flex-1 rounded-md border border-gray-300 px-2 py-1.5 text-sm"
            >
              <option value="">Select field...</option>
              {fields
                .filter(
                  (f) =>
                    ['number', 'decimal', 'integer', 'float', 'double'].includes(
                      f.dataType.toUpperCase(),
                    ) || agg.function === 'count',
                )
                .map((f) => (
                  <option key={f.code} value={f.code}>
                    {f.name}
                  </option>
                ))}
            </select>
            <select
              value={agg.function}
              onChange={(e) =>
                handleUpdateAggregation(index, {
                  function: e.target.value as KanbanAggregationConfig['function'],
                })
              }
              className="w-24 rounded-md border border-gray-300 px-2 py-1.5 text-sm"
            >
              <option value="count">Count</option>
              <option value="sum">Sum</option>
              <option value="avg">Avg</option>
              <option value="min">Min</option>
              <option value="max">Max</option>
            </select>
            <button
              type="button"
              onClick={() => handleRemoveAggregation(index)}
              className="p-1.5 text-gray-400 hover:text-red-500"
              title="Remove"
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

      {/* Toggle Options */}
      <div className="space-y-3 border-t border-gray-200 pt-2">
        <label className="flex cursor-pointer items-center gap-3">
          <input
            type="checkbox"
            checked={viewConfig.draggable ?? true}
            onChange={(e) => updateConfig({ draggable: e.target.checked })}
            className="h-4 w-4 rounded border-gray-300 text-blue-600 focus:ring-blue-500"
          />
          <span className="text-sm text-gray-700">Enable drag & drop</span>
        </label>
        <label className="flex cursor-pointer items-center gap-3">
          <input
            type="checkbox"
            checked={viewConfig.showCount ?? true}
            onChange={(e) => updateConfig({ showCount: e.target.checked })}
            className="h-4 w-4 rounded border-gray-300 text-blue-600 focus:ring-blue-500"
          />
          <span className="text-sm text-gray-700">Show card count</span>
        </label>
        <label className="flex cursor-pointer items-center gap-3">
          <input
            type="checkbox"
            checked={viewConfig.showAggregations ?? false}
            onChange={(e) => updateConfig({ showAggregations: e.target.checked })}
            className="h-4 w-4 rounded border-gray-300 text-blue-600 focus:ring-blue-500"
          />
          <span className="text-sm text-gray-700">Show aggregation values</span>
        </label>
      </div>
    </div>
  );
};

export default KanbanConfigPanel;
