/**
 * Field Configuration Dialog
 * Configure field binding properties (required, readonly, visible)
 */

import React, { useState } from 'react';
import type { ModelFieldBinding } from '~/types/model';

interface FieldConfigDialogProps {
  field: ModelFieldBinding;
  onSave: (config: FieldBindingConfig) => Promise<void>;
  onClose: () => void;
}

export interface FieldBindingConfig {
  required: boolean;
  readonly: boolean;
  visible: boolean;
  displayOrder?: number;
}

export function FieldConfigDialog({ field, onSave, onClose }: FieldConfigDialogProps) {
  const [config, setConfig] = useState<FieldBindingConfig>({
    required: field.required || false,
    readonly: field.readonly || false,
    visible: field.visible !== false,
    displayOrder: field.displayOrder,
  });
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setSaving(true);
    setError(null);

    try {
      await onSave(config);
      onClose();
    } catch (err) {
      setError(err instanceof Error ? err.message : '保存失败');
    } finally {
      setSaving(false);
    }
  };

  const handleBackdropClick = (e: React.MouseEvent) => {
    if (e.target === e.currentTarget) {
      onClose();
    }
  };

  return (
    <div
      className="bg-opacity-75 fixed inset-0 z-50 flex items-center justify-center bg-gray-500"
      onClick={handleBackdropClick}
    >
      <div className="mx-4 w-full max-w-md rounded-lg bg-white shadow-xl">
        {/* Header */}
        <div className="border-b border-gray-200 px-6 py-4">
          <div className="flex items-center justify-between">
            <h3 className="text-lg font-medium text-gray-900">配置字段绑定</h3>
            <button onClick={onClose} className="text-gray-400 hover:text-gray-500">
              <svg className="h-6 w-6" fill="none" stroke="currentColor" viewBox="0 0 24 24">
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

        {/* Body */}
        <form onSubmit={handleSubmit}>
          <div className="space-y-4 px-6 py-4">
            {/* Field Info */}
            <div className="rounded-md bg-gray-50 p-3">
              <div className="text-sm">
                <span className="text-gray-600">字段编码:</span>
                <span className="ml-2 font-mono text-gray-900">{field.fieldCode}</span>
              </div>
              <div className="mt-1 text-sm">
                <span className="text-gray-600">字段名称:</span>
                <span className="ml-2 text-gray-900">{field.fieldName || '-'}</span>
              </div>
              <div className="mt-1 text-sm">
                <span className="text-gray-600">数据类型:</span>
                <span className="ml-2 text-gray-900">{field.dataType}</span>
              </div>
            </div>

            {/* Error Message */}
            {error && (
              <div className="rounded-md border border-red-200 bg-red-50 p-3">
                <div className="flex">
                  <svg
                    className="mr-2 h-5 w-5 text-red-400"
                    fill="currentColor"
                    viewBox="0 0 20 20"
                  >
                    <path
                      fillRule="evenodd"
                      d="M10 18a8 8 0 100-16 8 8 0 000 16zM8.707 7.293a1 1 0 00-1.414 1.414L8.586 10l-1.293 1.293a1 1 0 101.414 1.414L10 11.414l1.293 1.293a1 1 0 001.414-1.414L11.414 10l1.293-1.293a1 1 0 00-1.414-1.414L10 8.586 8.707 7.293z"
                      clipRule="evenodd"
                    />
                  </svg>
                  <span className="text-sm text-red-800">{error}</span>
                </div>
              </div>
            )}

            {/* Configuration Options */}
            <div className="space-y-3">
              <label className="flex items-center">
                <input
                  type="checkbox"
                  checked={config.required}
                  onChange={(e) => setConfig({ ...config, required: e.target.checked })}
                  className="h-4 w-4 rounded border-gray-300 text-blue-600 focus:ring-blue-500"
                />
                <span className="ml-3 text-sm text-gray-700">
                  <span className="font-medium">必填字段</span>
                  <span className="block text-gray-500">用户必须填写此字段</span>
                </span>
              </label>

              <label className="flex items-center">
                <input
                  type="checkbox"
                  checked={config.readonly}
                  onChange={(e) => setConfig({ ...config, readonly: e.target.checked })}
                  className="h-4 w-4 rounded border-gray-300 text-blue-600 focus:ring-blue-500"
                />
                <span className="ml-3 text-sm text-gray-700">
                  <span className="font-medium">只读字段</span>
                  <span className="block text-gray-500">用户只能查看,不能编辑</span>
                </span>
              </label>

              <label className="flex items-center">
                <input
                  type="checkbox"
                  checked={config.visible}
                  onChange={(e) => setConfig({ ...config, visible: e.target.checked })}
                  className="h-4 w-4 rounded border-gray-300 text-blue-600 focus:ring-blue-500"
                />
                <span className="ml-3 text-sm text-gray-700">
                  <span className="font-medium">显示字段</span>
                  <span className="block text-gray-500">在表单和列表中显示此字段</span>
                </span>
              </label>
            </div>

            {/* Display Order */}
            <div>
              <label className="mb-2 block text-sm font-medium text-gray-700">显示顺序</label>
              <input
                type="number"
                value={config.displayOrder || 0}
                onChange={(e) =>
                  setConfig({ ...config, displayOrder: parseInt(e.target.value) || 0 })
                }
                className="w-full rounded-md border border-gray-300 px-3 py-2 focus:ring-2 focus:ring-blue-500 focus:outline-none"
                min="0"
              />
              <p className="mt-1 text-xs text-gray-500">数字越小,显示越靠前</p>
            </div>
          </div>

          {/* Footer */}
          <div className="flex justify-end space-x-3 border-t border-gray-200 bg-gray-50 px-6 py-4">
            <button
              type="button"
              onClick={onClose}
              className="rounded-md border border-gray-300 px-4 py-2 text-gray-700 hover:bg-gray-50 focus:ring-2 focus:ring-gray-500 focus:outline-none"
              disabled={saving}
            >
              取消
            </button>
            <button
              type="submit"
              className="rounded-md bg-blue-600 px-4 py-2 text-white hover:bg-blue-700 focus:ring-2 focus:ring-blue-500 focus:outline-none disabled:opacity-50"
              disabled={saving}
            >
              {saving ? '保存中...' : '保存'}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
