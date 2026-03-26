/**
 * Static DataSource Editor
 *
 * Editor for configuring static data sources.
 *
 * @since 3.2.0
 */

import React, { useCallback, useState } from 'react';
import type { StaticDataSourceConfig, DataSourceEditorProps } from '../types';

/**
 * Static DataSource Editor Component
 */
export const StaticDataSourceEditor: React.FC<DataSourceEditorProps<StaticDataSourceConfig>> = ({
  value,
  onChange,
}) => {
  const [editMode, setEditMode] = useState<'visual' | 'json'>('visual');
  const [jsonError, setJsonError] = useState<string | null>(null);

  // Add new item
  const addItem = useCallback(() => {
    const newData = [
      ...(value.data || []),
      {
        value: `option${(value.data?.length || 0) + 1}`,
        label: `选项 ${(value.data?.length || 0) + 1}`,
      },
    ];
    onChange({ data: newData });
  }, [value, onChange]);

  // Update item
  const updateItem = useCallback(
    (index: number, field: 'value' | 'label', newValue: string) => {
      const newData = [...(value.data || [])];
      newData[index] = { ...newData[index], [field]: newValue };
      onChange({ data: newData });
    },
    [value, onChange],
  );

  // Remove item
  const removeItem = useCallback(
    (index: number) => {
      const newData = value.data?.filter((_, i) => i !== index) || [];
      onChange({ data: newData });
    },
    [value, onChange],
  );

  // Move item
  const moveItem = useCallback(
    (index: number, direction: 'up' | 'down') => {
      const newData = [...(value.data || [])];
      const targetIndex = direction === 'up' ? index - 1 : index + 1;
      if (targetIndex < 0 || targetIndex >= newData.length) return;
      [newData[index], newData[targetIndex]] = [newData[targetIndex], newData[index]];
      onChange({ data: newData });
    },
    [value, onChange],
  );

  // Handle JSON change
  const handleJsonChange = useCallback(
    (jsonStr: string) => {
      try {
        const parsed = JSON.parse(jsonStr);
        if (!Array.isArray(parsed)) {
          setJsonError('数据必须是数组格式');
          return;
        }
        setJsonError(null);
        onChange({ data: parsed });
      } catch (e) {
        setJsonError('JSON 格式错误');
      }
    },
    [onChange],
  );

  return (
    <div className="space-y-3">
      {/* Mode Toggle */}
      <div className="flex items-center gap-2">
        <button
          type="button"
          onClick={() => setEditMode('visual')}
          className={`rounded px-2 py-1 text-xs transition-colors ${
            editMode === 'visual'
              ? 'bg-blue-100 text-blue-700'
              : 'bg-gray-100 text-gray-600 hover:bg-gray-200'
          }`}
        >
          可视化
        </button>
        <button
          type="button"
          onClick={() => setEditMode('json')}
          className={`rounded px-2 py-1 text-xs transition-colors ${
            editMode === 'json'
              ? 'bg-blue-100 text-blue-700'
              : 'bg-gray-100 text-gray-600 hover:bg-gray-200'
          }`}
        >
          JSON
        </button>
      </div>

      {/* Visual Editor */}
      {editMode === 'visual' && (
        <div className="space-y-2">
          {/* Header */}
          <div className="flex items-center px-1 text-[10px] text-gray-500">
            <span className="w-1/3">值 (value)</span>
            <span className="w-1/2">标签 (label)</span>
          </div>

          {/* Items */}
          {value.data && value.data.length > 0 ? (
            <div className="space-y-1.5">
              {value.data.map((item, index) => (
                <div key={index} className="flex items-center gap-1.5">
                  <input
                    type="text"
                    value={item.value}
                    onChange={(e) => updateItem(index, 'value', e.target.value)}
                    className="w-1/3 rounded border border-gray-200 px-2 py-1 font-mono text-[11px] focus:ring-1 focus:ring-blue-500 focus:outline-none"
                    placeholder="value"
                  />
                  <input
                    type="text"
                    value={item.label}
                    onChange={(e) => updateItem(index, 'label', e.target.value)}
                    className="w-1/2 rounded border border-gray-200 px-2 py-1 text-[11px] focus:ring-1 focus:ring-blue-500 focus:outline-none"
                    placeholder="显示文本"
                  />

                  {/* Actions */}
                  <div className="flex items-center gap-0.5">
                    <button
                      type="button"
                      onClick={() => moveItem(index, 'up')}
                      disabled={index === 0}
                      className="p-1 text-gray-400 hover:text-gray-600 disabled:opacity-30"
                    >
                      <svg
                        className="h-3 w-3"
                        fill="none"
                        stroke="currentColor"
                        viewBox="0 0 24 24"
                      >
                        <path
                          strokeLinecap="round"
                          strokeLinejoin="round"
                          strokeWidth={2}
                          d="M5 15l7-7 7 7"
                        />
                      </svg>
                    </button>
                    <button
                      type="button"
                      onClick={() => moveItem(index, 'down')}
                      disabled={index === value.data.length - 1}
                      className="p-1 text-gray-400 hover:text-gray-600 disabled:opacity-30"
                    >
                      <svg
                        className="h-3 w-3"
                        fill="none"
                        stroke="currentColor"
                        viewBox="0 0 24 24"
                      >
                        <path
                          strokeLinecap="round"
                          strokeLinejoin="round"
                          strokeWidth={2}
                          d="M19 9l-7 7-7-7"
                        />
                      </svg>
                    </button>
                    <button
                      type="button"
                      onClick={() => removeItem(index)}
                      className="p-1 text-gray-400 hover:text-red-500"
                    >
                      <svg
                        className="h-3 w-3"
                        fill="none"
                        stroke="currentColor"
                        viewBox="0 0 24 24"
                      >
                        <path
                          strokeLinecap="round"
                          strokeLinejoin="round"
                          strokeWidth={2}
                          d="M6 18L18 6M6 6l12 12"
                        />
                      </svg>
                    </button>
                  </div>
                </div>
              ))}
            </div>
          ) : (
            <div className="rounded-md border border-dashed border-gray-200 py-3 text-center text-[10px] text-gray-400">
              暂无数据，点击下方添加
            </div>
          )}

          {/* Add Button */}
          <button
            type="button"
            onClick={addItem}
            className="w-full rounded border border-dashed border-blue-200 py-1.5 text-xs text-blue-600 transition-colors hover:bg-blue-50 hover:text-blue-700"
          >
            + 添加选项
          </button>
        </div>
      )}

      {/* JSON Editor */}
      {editMode === 'json' && (
        <div className="space-y-2">
          <textarea
            value={JSON.stringify(value.data || [], null, 2)}
            onChange={(e) => handleJsonChange(e.target.value)}
            className={`h-40 w-full rounded-md border px-2 py-2 font-mono text-xs focus:ring-1 focus:outline-none ${
              jsonError
                ? 'border-red-300 focus:ring-red-500'
                : 'border-gray-200 focus:ring-blue-500'
            }`}
            placeholder='[{"value": "key", "label": "显示文本"}]'
          />
          {jsonError && <p className="text-[10px] text-red-500">{jsonError}</p>}
          <p className="text-[10px] text-gray-500">
            数据格式: [{'{'}"value": "...", "label": "..."{'}'}, ...]
          </p>
        </div>
      )}

      {/* Item Count */}
      <div className="text-right text-[10px] text-gray-400">共 {value.data?.length || 0} 项</div>
    </div>
  );
};

export default StaticDataSourceEditor;
