/**
 * ImportPanel Component
 *
 * Modal for importing page schema from file.
 *
 * @since 3.2.0
 */

import React, { useState, useCallback, useRef } from 'react';
import {
  EXPORT_VERSION,
  type ImportValidationResult,
  type ImportOptions,
  type ExportedPageData,
} from './types';
import type { FormSchema } from '~/plugins/core-designer/components/studio/domain/schema/types';

/**
 * ImportPanel props
 */
export interface ImportPanelProps {
  /** Whether the panel is open */
  isOpen: boolean;
  /** Close callback */
  onClose: () => void;
  /** Import success callback */
  onImport: (schema: FormSchema, metadata?: ExportedPageData['metadata']) => Promise<void>;
  /** Current page exists */
  hasExistingPage?: boolean;
}

/**
 * ImportPanel component
 */
export const ImportPanel: React.FC<ImportPanelProps> = ({
  isOpen,
  onClose,
  onImport,
  hasExistingPage = false,
}) => {
  const [file, setFile] = useState<File | null>(null);
  const [validation, setValidation] = useState<ImportValidationResult | null>(null);
  const [options, setOptions] = useState<ImportOptions>({
    overwriteExisting: false,
    importAsNew: !hasExistingPage,
    customTitle: '',
  });
  const [importing, setImporting] = useState(false);
  const [dragActive, setDragActive] = useState(false);
  const fileInputRef = useRef<HTMLInputElement>(null);

  // Validate imported data
  const validateImportData = useCallback((content: string): ImportValidationResult => {
    const result: ImportValidationResult = {
      isValid: false,
      errors: [],
      warnings: [],
    };

    try {
      const data = JSON.parse(content) as ExportedPageData;

      // Check export version
      if (!data.exportVersion) {
        result.warnings.push('缺少导出版本信息，可能是旧版本格式');
      } else if (data.exportVersion !== EXPORT_VERSION) {
        result.warnings.push(`版本不匹配: 期望 ${EXPORT_VERSION}, 实际 ${data.exportVersion}`);
      }

      // Check schema
      if (!data.schema) {
        result.errors.push('缺少页面 Schema');
      } else if (typeof data.schema !== 'object') {
        result.errors.push('Schema 格式无效');
      }

      // Check components
      if (data.schema && !data.schema.components) {
        result.warnings.push('Schema 中没有组件定义');
      }

      if (result.errors.length === 0) {
        result.isValid = true;
        result.data = data;
      }
    } catch (error) {
      result.errors.push('JSON 解析失败: 请检查文件格式');
    }

    return result;
  }, []);

  // Handle file selection
  const handleFileSelect = useCallback(
    (selectedFile: File) => {
      setFile(selectedFile);
      setValidation(null);

      const reader = new FileReader();
      reader.onload = (e) => {
        const content = e.target?.result as string;
        setValidation(validateImportData(content));
      };
      reader.onerror = () => {
        setValidation({
          isValid: false,
          errors: ['文件读取失败'],
          warnings: [],
        });
      };
      reader.readAsText(selectedFile);
    },
    [validateImportData],
  );

  // Handle file input change
  const handleInputChange = useCallback(
    (e: React.ChangeEvent<HTMLInputElement>) => {
      const selectedFile = e.target.files?.[0];
      if (selectedFile) {
        handleFileSelect(selectedFile);
      }
    },
    [handleFileSelect],
  );

  // Handle drag events
  const handleDrag = useCallback((e: React.DragEvent) => {
    e.preventDefault();
    e.stopPropagation();
    if (e.type === 'dragenter' || e.type === 'dragover') {
      setDragActive(true);
    } else if (e.type === 'dragleave') {
      setDragActive(false);
    }
  }, []);

  // Handle drop
  const handleDrop = useCallback(
    (e: React.DragEvent) => {
      e.preventDefault();
      e.stopPropagation();
      setDragActive(false);

      const droppedFile = e.dataTransfer.files?.[0];
      if (droppedFile && droppedFile.type === 'application/json') {
        handleFileSelect(droppedFile);
      } else {
        setValidation({
          isValid: false,
          errors: ['只支持 JSON 格式文件'],
          warnings: [],
        });
      }
    },
    [handleFileSelect],
  );

  // Handle import
  const handleImport = useCallback(async () => {
    if (!validation?.isValid || !validation.data) return;

    setImporting(true);
    try {
      const schema = validation.data.schema as unknown as FormSchema;
      const metadata = validation.data.metadata;
      const finalMetadata =
        options.importAsNew && options.customTitle && metadata
          ? { ...metadata, title: options.customTitle }
          : metadata;

      await onImport(schema, finalMetadata as ExportedPageData['metadata']);
      onClose();
    } catch (error) {
      console.error('Import failed:', error);
    } finally {
      setImporting(false);
    }
  }, [validation, options, onImport, onClose]);

  // Reset state
  const handleReset = useCallback(() => {
    setFile(null);
    setValidation(null);
    if (fileInputRef.current) {
      fileInputRef.current.value = '';
    }
  }, []);

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
            <div className="flex h-10 w-10 items-center justify-center rounded-lg bg-blue-100">
              <svg
                className="h-5 w-5 text-blue-600"
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
              <h2 className="text-lg font-semibold text-gray-900">导入页面</h2>
              <p className="text-sm text-gray-500">从 JSON 文件导入页面配置</p>
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
          {/* File drop zone */}
          <div
            className={`mb-6 rounded-xl border-2 border-dashed p-8 text-center transition-colors ${
              dragActive
                ? 'border-blue-500 bg-blue-50'
                : file
                  ? 'border-green-300 bg-green-50'
                  : 'border-gray-300 hover:border-gray-400'
            }`}
            onDragEnter={handleDrag}
            onDragLeave={handleDrag}
            onDragOver={handleDrag}
            onDrop={handleDrop}
          >
            <input
              ref={fileInputRef}
              type="file"
              accept=".json"
              onChange={handleInputChange}
              className="hidden"
            />

            {file ? (
              <div className="space-y-3">
                <div className="mx-auto flex h-12 w-12 items-center justify-center rounded-full bg-green-100">
                  <svg
                    className="h-6 w-6 text-green-600"
                    fill="none"
                    viewBox="0 0 24 24"
                    stroke="currentColor"
                  >
                    <path
                      strokeLinecap="round"
                      strokeLinejoin="round"
                      strokeWidth={2}
                      d="M5 13l4 4L19 7"
                    />
                  </svg>
                </div>
                <div>
                  <p className="text-sm font-medium text-gray-900">{file.name}</p>
                  <p className="text-xs text-gray-500">{(file.size / 1024).toFixed(2)} KB</p>
                </div>
                <button onClick={handleReset} className="text-sm text-blue-600 hover:text-blue-700">
                  选择其他文件
                </button>
              </div>
            ) : (
              <div className="space-y-3">
                <div className="mx-auto flex h-12 w-12 items-center justify-center rounded-full bg-gray-100">
                  <svg
                    className="h-6 w-6 text-gray-400"
                    fill="none"
                    viewBox="0 0 24 24"
                    stroke="currentColor"
                  >
                    <path
                      strokeLinecap="round"
                      strokeLinejoin="round"
                      strokeWidth={2}
                      d="M7 16a4 4 0 01-.88-7.903A5 5 0 1115.9 6L16 6a5 5 0 011 9.9M15 13l-3-3m0 0l-3 3m3-3v12"
                    />
                  </svg>
                </div>
                <div>
                  <p className="text-sm text-gray-600">
                    拖放文件到这里，或{' '}
                    <button
                      onClick={() => fileInputRef.current?.click()}
                      className="text-blue-600 hover:text-blue-700"
                    >
                      点击选择
                    </button>
                  </p>
                  <p className="mt-1 text-xs text-gray-400">支持 .json 格式</p>
                </div>
              </div>
            )}
          </div>

          {/* Validation result */}
          {validation && (
            <div className="mb-6">
              {validation.errors.length > 0 && (
                <div className="mb-4 rounded-lg border border-red-200 bg-red-50 p-4">
                  <div className="mb-2 flex items-center gap-2">
                    <svg
                      className="h-5 w-5 text-red-600"
                      fill="none"
                      viewBox="0 0 24 24"
                      stroke="currentColor"
                    >
                      <path
                        strokeLinecap="round"
                        strokeLinejoin="round"
                        strokeWidth={2}
                        d="M12 8v4m0 4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z"
                      />
                    </svg>
                    <span className="text-sm font-medium text-red-800">验证失败</span>
                  </div>
                  <ul className="space-y-1 text-sm text-red-700">
                    {validation.errors.map((error, index) => (
                      <li key={index}>• {error}</li>
                    ))}
                  </ul>
                </div>
              )}

              {validation.warnings.length > 0 && (
                <div className="mb-4 rounded-lg border border-yellow-200 bg-yellow-50 p-4">
                  <div className="mb-2 flex items-center gap-2">
                    <svg
                      className="h-5 w-5 text-yellow-600"
                      fill="none"
                      viewBox="0 0 24 24"
                      stroke="currentColor"
                    >
                      <path
                        strokeLinecap="round"
                        strokeLinejoin="round"
                        strokeWidth={2}
                        d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-3L13.732 4c-.77-1.333-2.694-1.333-3.464 0L3.34 16c-.77 1.333.192 3 1.732 3z"
                      />
                    </svg>
                    <span className="text-sm font-medium text-yellow-800">警告</span>
                  </div>
                  <ul className="space-y-1 text-sm text-yellow-700">
                    {validation.warnings.map((warning, index) => (
                      <li key={index}>• {warning}</li>
                    ))}
                  </ul>
                </div>
              )}

              {validation.isValid && validation.data && (
                <div className="rounded-lg border border-green-200 bg-green-50 p-4">
                  <div className="mb-3 flex items-center gap-2">
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
                        d="M5 13l4 4L19 7"
                      />
                    </svg>
                    <span className="text-sm font-medium text-green-800">验证通过</span>
                  </div>
                  {validation.data.metadata && (
                    <div className="grid grid-cols-2 gap-2 text-sm">
                      <div>
                        <span className="text-gray-500">标题:</span>{' '}
                        <span className="text-gray-900">{validation.data.metadata.title}</span>
                      </div>
                      <div>
                        <span className="text-gray-500">版本:</span>{' '}
                        <span className="text-gray-900">{validation.data.metadata.version}</span>
                      </div>
                      <div>
                        <span className="text-gray-500">组件数:</span>{' '}
                        <span className="text-gray-900">
                          {(validation.data.schema.components as unknown[])?.length || 0}
                        </span>
                      </div>
                    </div>
                  )}
                </div>
              )}
            </div>
          )}

          {/* Import options */}
          {validation?.isValid && hasExistingPage && (
            <div className="space-y-4">
              <label className="block text-sm font-medium text-gray-700">导入选项</label>

              <label className="flex cursor-pointer items-center gap-3 rounded-lg bg-gray-50 p-3">
                <input
                  type="radio"
                  name="importOption"
                  checked={options.overwriteExisting}
                  onChange={() =>
                    setOptions((prev) => ({
                      ...prev,
                      overwriteExisting: true,
                      importAsNew: false,
                    }))
                  }
                  className="h-4 w-4 text-blue-600"
                />
                <div>
                  <p className="text-sm font-medium text-gray-900">覆盖当前页面</p>
                  <p className="text-xs text-gray-500">将当前页面内容替换为导入的内容</p>
                </div>
              </label>

              <label className="flex cursor-pointer items-center gap-3 rounded-lg bg-gray-50 p-3">
                <input
                  type="radio"
                  name="importOption"
                  checked={options.importAsNew}
                  onChange={() =>
                    setOptions((prev) => ({
                      ...prev,
                      overwriteExisting: false,
                      importAsNew: true,
                    }))
                  }
                  className="h-4 w-4 text-blue-600"
                />
                <div>
                  <p className="text-sm font-medium text-gray-900">创建新页面</p>
                  <p className="text-xs text-gray-500">保留当前页面，创建一个新页面</p>
                </div>
              </label>

              {options.importAsNew && (
                <div className="pl-7">
                  <label className="mb-1 block text-sm text-gray-600">新页面标题</label>
                  <input
                    type="text"
                    value={options.customTitle}
                    onChange={(e) =>
                      setOptions((prev) => ({ ...prev, customTitle: e.target.value }))
                    }
                    placeholder={validation.data?.metadata?.title || '页面标题'}
                    className="w-full rounded-lg border border-gray-300 px-3 py-2 focus:ring-2 focus:ring-blue-500 focus:outline-none"
                  />
                </div>
              )}
            </div>
          )}
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
            onClick={handleImport}
            disabled={!validation?.isValid || importing}
            className="flex items-center gap-2 rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700 disabled:cursor-not-allowed disabled:opacity-50"
          >
            {importing && (
              <div className="h-4 w-4 animate-spin rounded-full border-2 border-white border-t-transparent" />
            )}
            导入
          </button>
        </div>
      </div>
    </div>
  );
};

export default ImportPanel;
