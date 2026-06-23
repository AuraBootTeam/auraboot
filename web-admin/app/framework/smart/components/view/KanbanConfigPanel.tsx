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
} from '~/framework/smart/types/savedView';
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
          分组字段 <span className="text-red-500">*</span>
        </label>
        <select
          value={viewConfig.groupByField || ''}
          onChange={(e) => updateConfig({ groupByField: e.target.value })}
          className={selectClassName}
        >
          <option value="">选择字段...</option>
          {fields.map((f) => (
            <option key={f.code} value={f.code}>
              {f.name} ({f.dataType})
            </option>
          ))}
        </select>
        <p className="mt-1 text-xs text-gray-500">
          看板卡片会按这个字段的取值分组。
        </p>
      </div>

      {/* Title Field (required) */}
      <div>
        <label className="mb-1 block text-sm font-medium text-gray-700">
          标题字段 <span className="text-red-500">*</span>
        </label>
        <select
          value={viewConfig.titleField || ''}
          onChange={(e) => updateConfig({ titleField: e.target.value })}
          className={selectClassName}
        >
          <option value="">选择字段...</option>
          {fields.map((f) => (
            <option key={f.code} value={f.code}>
              {f.name}
            </option>
          ))}
        </select>
      </div>

      {/* Description Field (optional) */}
      <div>
        <label className="mb-1 block text-sm font-medium text-gray-700">描述字段</label>
        <select
          value={viewConfig.descriptionField || ''}
          onChange={(e) =>
            updateConfig({
              descriptionField: e.target.value || undefined,
            })
          }
          className={selectClassName}
        >
          <option value="">不显示</option>
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
          <label className="text-sm font-medium text-gray-700">卡片字段</label>
          <button
            type="button"
            onClick={handleAddCardField}
            className="text-sm text-blue-600 hover:text-blue-700"
          >
            + 添加字段
          </button>
        </div>
        {(viewConfig.cardFields || []).map((cf, index) => (
          <div key={index} className="mb-2 flex items-center gap-2">
            <select
              value={cf.field}
              onChange={(e) => handleUpdateCardField(index, { field: e.target.value })}
              className="flex-1 rounded-md border border-gray-300 px-2 py-1.5 text-sm"
            >
              <option value="">选择字段...</option>
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
              <option value="text">文本</option>
              <option value="number">数字</option>
              <option value="date">日期</option>
              <option value="tag">标签</option>
              <option value="avatar">头像</option>
            </select>
            <button
              type="button"
              onClick={() => handleRemoveCardField(index)}
              className="p-1.5 text-gray-400 hover:text-red-500"
              title="移除"
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
          <label className="text-sm font-medium text-gray-700">列聚合</label>
          <button
            type="button"
            onClick={handleAddAggregation}
            className="text-sm text-blue-600 hover:text-blue-700"
          >
            + 添加聚合
          </button>
        </div>
        {(viewConfig.kanbanAggregations || []).map((agg, index) => (
          <div key={index} className="mb-2 flex items-center gap-2">
            <select
              value={agg.field}
              onChange={(e) => handleUpdateAggregation(index, { field: e.target.value })}
              className="flex-1 rounded-md border border-gray-300 px-2 py-1.5 text-sm"
            >
              <option value="">选择字段...</option>
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
              <option value="count">计数</option>
              <option value="sum">求和</option>
              <option value="avg">平均值</option>
              <option value="min">最小值</option>
              <option value="max">最大值</option>
            </select>
            <button
              type="button"
              onClick={() => handleRemoveAggregation(index)}
              className="p-1.5 text-gray-400 hover:text-red-500"
              title="移除"
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
          <span className="text-sm text-gray-700">启用拖拽</span>
        </label>
        <label className="flex cursor-pointer items-center gap-3">
          <input
            type="checkbox"
            checked={viewConfig.showCount ?? true}
            onChange={(e) => updateConfig({ showCount: e.target.checked })}
            className="h-4 w-4 rounded border-gray-300 text-blue-600 focus:ring-blue-500"
          />
          <span className="text-sm text-gray-700">显示卡片数量</span>
        </label>
        <label className="flex cursor-pointer items-center gap-3">
          <input
            type="checkbox"
            checked={viewConfig.showAggregations ?? false}
            onChange={(e) => updateConfig({ showAggregations: e.target.checked })}
            className="h-4 w-4 rounded border-gray-300 text-blue-600 focus:ring-blue-500"
          />
          <span className="text-sm text-gray-700">显示聚合值</span>
        </label>
      </div>
    </div>
  );
};

export default KanbanConfigPanel;
