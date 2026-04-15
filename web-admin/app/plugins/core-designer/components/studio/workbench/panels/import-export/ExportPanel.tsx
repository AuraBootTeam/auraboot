/**
 * ExportPanel Component
 *
 * Modal for exporting page schema to file.
 *
 * @since 3.2.0
 */

import React, { useState, useCallback, useMemo } from 'react';
import {
  DEFAULT_EXPORT_OPTIONS,
  EXPORT_VERSION,
  type ExportOptions,
  type ExportedPageData,
  type ExportFormat,
} from './types';
import type { FormSchema } from '~/plugins/core-designer/components/studio/domain/schema/types';
import type { PageMeta } from '~/plugins/core-designer/components/studio/services/page-manager';

/**
 * ExportPanel props
 */
export interface ExportPanelProps {
  /** Whether the panel is open */
  isOpen: boolean;
  /** Close callback */
  onClose: () => void;
  /** Page metadata */
  pageMeta?: PageMeta;
  /** Page schema to export */
  schema?: FormSchema;
}

/**
 * ExportPanel component
 */
export const ExportPanel: React.FC<ExportPanelProps> = ({ isOpen, onClose, pageMeta, schema }) => {
  const [options, setOptions] = useState<ExportOptions>(DEFAULT_EXPORT_OPTIONS);
  const [exporting, setExporting] = useState(false);

  // Generate export data
  const exportData = useMemo((): ExportedPageData => {
    const data: ExportedPageData = {
      exportVersion: EXPORT_VERSION,
      exportedAt: new Date().toISOString(),
      schema: (schema as unknown as Record<string, unknown>) || { components: [] },
    };

    if (options.includeMetadata && pageMeta) {
      data.metadata = {
        title: pageMeta.title,
        description: pageMeta.description,
        version: pageMeta.version,
        createdAt: pageMeta.createdAt,
        updatedAt: pageMeta.updatedAt,
        tags: pageMeta.tags,
      };
    }

    return data;
  }, [options, pageMeta, schema]);

  // Generate export string
  const exportString = useMemo(() => {
    if (options.format === 'json') {
      return options.prettyPrint ? JSON.stringify(exportData, null, 2) : JSON.stringify(exportData);
    }
    // YAML format - simplified implementation
    return JSON.stringify(exportData, null, 2);
  }, [exportData, options]);

  // Handle export
  const handleExport = useCallback(() => {
    setExporting(true);

    try {
      const filename = `${pageMeta?.title || 'page'}_${new Date().toISOString().split('T')[0]}.${options.format}`;
      const blob = new Blob([exportString], { type: 'application/json' });
      const url = URL.createObjectURL(blob);

      const a = document.createElement('a');
      a.href = url;
      a.download = filename;
      document.body.appendChild(a);
      a.click();
      document.body.removeChild(a);
      URL.revokeObjectURL(url);

      onClose();
    } catch (error) {
      console.error('Export failed:', error);
    } finally {
      setExporting(false);
    }
  }, [exportString, options.format, pageMeta?.title, onClose]);

  // Copy to clipboard
  const handleCopyToClipboard = useCallback(async () => {
    try {
      await navigator.clipboard.writeText(exportString);
    } catch (error) {
      console.error('Copy failed:', error);
    }
  }, [exportString]);

  // Update option
  const updateOption = useCallback(
    <K extends keyof ExportOptions>(key: K, value: ExportOptions[K]) => {
      setOptions((prev) => ({ ...prev, [key]: value }));
    },
    [],
  );

  if (!isOpen) return null;

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center">
      {/* Backdrop */}
      <div className="absolute inset-0 bg-black/50" onClick={onClose} />

      {/* Panel */}
      <div className="relative flex max-h-[85vh] w-full max-w-2xl flex-col overflow-hidden rounded-xl bg-white shadow-2xl">
        {/* Header */}
        <div className="flex items-center justify-between border-b border-gray-200 px-6 py-4">
          <div className="flex items-center gap-3">
            <div className="flex h-10 w-10 items-center justify-center rounded-lg bg-green-100">
              <svg
                className="h-5 w-5 text-green-600"
                fill="none"
                viewBox="0 0 24 24"
                stroke="currentColor"
              >
                <path
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  strokeWidth={2}
                  d="M4 16v1a3 3 0 003 3h10a3 3 0 003-3v-1m-4-8l-4-4m0 0L8 8m4-4v12"
                />
              </svg>
            </div>
            <div>
              <h2 className="text-lg font-semibold text-gray-900">导出页面</h2>
              {pageMeta && <p className="text-sm text-gray-500">{pageMeta.title}</p>}
            </div>
          </div>
          <button onClick={onClose} className="rounded-lg p-1.5 text-gray-500 hover:bg-gray-100">
            <svg className="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={2}
                d="M6 18L18 6M6 6l12 12"
              />
            </svg>
          </button>
        </div>

        {/* Content */}
        <div className="flex-1 overflow-y-auto p-6">
          {/* Format selection */}
          <div className="mb-6">
            <label className="mb-2 block text-sm font-medium text-gray-700">导出格式</label>
            <div className="flex gap-3">
              {(['json'] as ExportFormat[]).map((format) => (
                <button
                  key={format}
                  onClick={() => updateOption('format', format)}
                  className={`rounded-lg border px-4 py-2 text-sm font-medium uppercase ${
                    options.format === format
                      ? 'border-blue-500 bg-blue-50 text-blue-700'
                      : 'border-gray-300 text-gray-600 hover:bg-gray-50'
                  }`}
                >
                  {format}
                </button>
              ))}
            </div>
          </div>

          {/* Options */}
          <div className="mb-6 space-y-4">
            <label className="mb-2 block text-sm font-medium text-gray-700">导出选项</label>

            <label className="flex cursor-pointer items-center justify-between rounded-lg bg-gray-50 p-3">
              <div>
                <p className="text-sm font-medium text-gray-900">包含元数据</p>
                <p className="text-xs text-gray-500">包含标题、描述、标签等信息</p>
              </div>
              <input
                type="checkbox"
                checked={options.includeMetadata}
                onChange={(e) => updateOption('includeMetadata', e.target.checked)}
                className="h-5 w-5 rounded border-gray-300 text-blue-600"
              />
            </label>

            <label className="flex cursor-pointer items-center justify-between rounded-lg bg-gray-50 p-3">
              <div>
                <p className="text-sm font-medium text-gray-900">格式化输出</p>
                <p className="text-xs text-gray-500">使用缩进使输出更易读</p>
              </div>
              <input
                type="checkbox"
                checked={options.prettyPrint}
                onChange={(e) => updateOption('prettyPrint', e.target.checked)}
                className="h-5 w-5 rounded border-gray-300 text-blue-600"
              />
            </label>

            <label className="flex cursor-pointer items-center justify-between rounded-lg bg-gray-50 p-3">
              <div>
                <p className="text-sm font-medium text-gray-900">包含版本历史</p>
                <p className="text-xs text-gray-500">导出所有版本记录</p>
              </div>
              <input
                type="checkbox"
                checked={options.includeVersionHistory}
                onChange={(e) => updateOption('includeVersionHistory', e.target.checked)}
                className="h-5 w-5 rounded border-gray-300 text-blue-600"
              />
            </label>
          </div>

          {/* Preview */}
          <div>
            <div className="mb-2 flex items-center justify-between">
              <label className="block text-sm font-medium text-gray-700">预览</label>
              <button
                onClick={handleCopyToClipboard}
                className="text-xs text-blue-600 hover:text-blue-700"
              >
                复制到剪贴板
              </button>
            </div>
            <pre className="max-h-64 overflow-auto rounded-lg bg-gray-900 p-4 font-mono text-xs text-gray-100">
              {exportString.length > 2000
                ? exportString.slice(0, 2000) + '\n... (truncated)'
                : exportString}
            </pre>
            <p className="mt-2 text-xs text-gray-500">
              文件大小: {(new Blob([exportString]).size / 1024).toFixed(2)} KB
            </p>
          </div>
        </div>

        {/* Footer */}
        <div className="flex items-center justify-end gap-3 border-t border-gray-200 bg-gray-50 px-6 py-4">
          <button
            onClick={onClose}
            className="rounded-lg px-4 py-2 text-sm text-gray-700 hover:bg-gray-200"
          >
            取消
          </button>
          <button
            onClick={handleExport}
            disabled={exporting}
            className="flex items-center gap-2 rounded-lg bg-green-600 px-4 py-2 text-sm font-medium text-white hover:bg-green-700 disabled:opacity-50"
          >
            {exporting && (
              <div className="h-4 w-4 animate-spin rounded-full border-2 border-white border-t-transparent" />
            )}
            <svg className="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={2}
                d="M4 16v1a3 3 0 003 3h10a3 3 0 003-3v-1m-4-4l-4 4m0 0l-4-4m4 4V4"
              />
            </svg>
            下载文件
          </button>
        </div>
      </div>
    </div>
  );
};

export default ExportPanel;
