import React, { useState } from 'react';
import type { PreviewFieldDef } from './types';

interface MockDataPanelProps {
  data: Record<string, any>;
  fields: PreviewFieldDef[];
  onRegenerate: () => void;
  onDataChange: (data: Record<string, any>) => void;
}

/**
 * MockDataPanel - side panel showing current mock data with edit capabilities.
 *
 * @since 3.6.0
 */
export const MockDataPanel: React.FC<MockDataPanelProps> = ({
  data,
  fields,
  onRegenerate,
  onDataChange,
}) => {
  const [editMode, setEditMode] = useState(false);
  const [jsonText, setJsonText] = useState('');
  const [jsonError, setJsonError] = useState<string | null>(null);

  const handleCopy = () => {
    navigator.clipboard.writeText(JSON.stringify(data, null, 2));
  };

  const handleEditToggle = () => {
    if (!editMode) {
      setJsonText(JSON.stringify(data, null, 2));
      setJsonError(null);
    }
    setEditMode(!editMode);
  };

  const handleJsonApply = () => {
    try {
      const parsed = JSON.parse(jsonText);
      onDataChange(parsed);
      setEditMode(false);
      setJsonError(null);
    } catch (e) {
      setJsonError('JSON 格式错误');
    }
  };

  return (
    <div className="flex h-full flex-col">
      {/* Header */}
      <div className="border-b border-gray-200 p-3">
        <div className="flex items-center justify-between">
          <span className="text-xs font-semibold text-gray-700">模拟数据</span>
          <div className="flex items-center gap-1">
            <button
              onClick={onRegenerate}
              className="rounded px-2 py-0.5 text-xs text-blue-600 hover:bg-blue-50"
              title="重新生成"
            >
              <svg
                className="mr-0.5 inline h-3.5 w-3.5"
                fill="none"
                stroke="currentColor"
                viewBox="0 0 24 24"
              >
                <path
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  strokeWidth={2}
                  d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15"
                />
              </svg>
              刷新
            </button>
            <button
              onClick={handleCopy}
              className="rounded px-2 py-0.5 text-xs text-gray-600 hover:bg-gray-100"
              title="复制 JSON"
            >
              复制
            </button>
          </div>
        </div>
      </div>

      {/* Data display */}
      <div className="flex-1 overflow-y-auto p-3">
        {editMode ? (
          <div className="space-y-2">
            <textarea
              value={jsonText}
              onChange={(e) => {
                setJsonText(e.target.value);
                setJsonError(null);
              }}
              className="h-48 w-full resize-none rounded border border-gray-300 px-2 py-1.5 font-mono text-xs focus:ring-1 focus:ring-blue-400 focus:outline-none"
              spellCheck={false}
            />
            {jsonError && <p className="text-xs text-red-500">{jsonError}</p>}
            <div className="flex items-center gap-2">
              <button
                onClick={handleJsonApply}
                className="rounded bg-blue-500 px-2 py-1 text-xs text-white hover:bg-blue-600"
              >
                应用
              </button>
              <button
                onClick={() => setEditMode(false)}
                className="px-2 py-1 text-xs text-gray-600 hover:text-gray-800"
              >
                取消
              </button>
            </div>
          </div>
        ) : (
          <div className="space-y-1.5">
            {fields.map((field) => (
              <div key={field.code} className="flex items-start gap-2">
                <span
                  className="w-20 shrink-0 truncate pt-0.5 text-right text-[10px] text-gray-400"
                  title={field.code}
                >
                  {field.label || field.code}
                </span>
                <span className="font-mono text-xs break-all text-gray-700">
                  {formatValue(data[field.code])}
                </span>
              </div>
            ))}
            {fields.length === 0 && (
              <div className="py-4 text-center text-xs text-gray-400">暂无字段定义</div>
            )}
          </div>
        )}
      </div>

      {/* Footer */}
      <div className="border-t border-gray-200 p-3">
        <button
          onClick={handleEditToggle}
          className="w-full rounded border border-gray-300 py-1.5 text-xs text-gray-600 hover:bg-gray-50"
        >
          {editMode ? '返回列表' : '编辑 JSON'}
        </button>
      </div>
    </div>
  );
};

function formatValue(value: any): string {
  if (value === null || value === undefined) return '—';
  if (typeof value === 'boolean') return value ? 'true' : 'false';
  if (typeof value === 'object') return JSON.stringify(value);
  return String(value);
}
