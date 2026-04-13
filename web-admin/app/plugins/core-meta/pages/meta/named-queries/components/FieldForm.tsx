/**
 * Full field attribute editor in a Drawer / slide-over panel.
 * Replaces the previous inline editing row to expose all backend-supported properties.
 */

import React, { useState, useEffect } from 'react';
import type { NamedQueryFieldRequest } from '~/shared/services/namedQueryService';
import {
  DATA_TYPES,
  ALL_OPERATORS,
  OPERATOR_LABELS,
  OPERATORS_BY_TYPE,
  UI_COMPONENTS,
} from './constants';

interface FieldFormProps {
  mode: 'add' | 'edit';
  initialData?: Partial<NamedQueryFieldRequest>;
  onSave: (field: NamedQueryFieldRequest) => void;
  onCancel: () => void;
  loading?: boolean;
}

const EMPTY_FIELD: NamedQueryFieldRequest = {
  fieldCode: '',
  columnExpr: '',
  dataType: 'string',
  operators: ['eq'],
  sortable: false,
  searchable: true,
};

export default function FieldForm({
  mode,
  initialData,
  onSave,
  onCancel,
  loading,
}: FieldFormProps) {
  const [field, setField] = useState<NamedQueryFieldRequest>({ ...EMPTY_FIELD, ...initialData });
  const [showUiHints, setShowUiHints] = useState(
    !!(
      initialData?.uiComponent ||
      initialData?.placeholder ||
      initialData?.linkedField ||
      initialData?.fieldGroup
    ),
  );

  useEffect(() => {
    setField({ ...EMPTY_FIELD, ...initialData });
  }, [initialData]);

  const update = <K extends keyof NamedQueryFieldRequest>(
    key: K,
    value: NamedQueryFieldRequest[K],
  ) => {
    setField((prev) => {
      const next = { ...prev, [key]: value };
      // Auto-populate default operators when data type changes
      if (key === 'dataType') {
        next.operators = OPERATORS_BY_TYPE[value as string] || ['eq'];
      }
      return next;
    });
  };

  const toggleOperator = (op: string) => {
    const current = field.operators || [];
    if (current.includes(op)) {
      update(
        'operators',
        current.filter((o) => o !== op),
      );
    } else {
      update('operators', [...current, op]);
    }
  };

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    onSave(field);
  };

  return (
    <>
      {/* Backdrop */}
      <div className="fixed inset-0 z-40 bg-black/30" onClick={onCancel} />

      {/* Drawer */}
      <div className="fixed inset-y-0 right-0 z-50 flex w-full max-w-lg flex-col bg-white shadow-xl">
        {/* Header */}
        <div className="flex items-center justify-between border-b border-gray-200 px-6 py-4">
          <h3 className="text-lg font-medium text-gray-900">
            {mode === 'add' ? '添加字段' : `编辑字段: ${initialData?.fieldCode || ''}`}
          </h3>
          <button type="button" onClick={onCancel} className="text-gray-400 hover:text-gray-600">
            <svg className="h-5 w-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={2}
                d="M6 18L18 6M6 6l12 12"
              />
            </svg>
          </button>
        </div>

        {/* Body */}
        <form onSubmit={handleSubmit} className="flex-1 space-y-6 overflow-y-auto px-6 py-5">
          {/* Section 1: Core */}
          <fieldset className="space-y-4">
            <legend className="text-sm font-semibold tracking-wider text-gray-900 uppercase">
              核心属性
            </legend>
            <div className="grid grid-cols-2 gap-4">
              <div>
                <label className="mb-1 block text-sm font-medium text-gray-700">
                  字段编码 <span className="text-red-500">*</span>
                </label>
                <input
                  type="text"
                  value={field.fieldCode}
                  onChange={(e) => update('fieldCode', e.target.value)}
                  disabled={mode === 'edit'}
                  placeholder="e.g. user_name"
                  className="w-full rounded-md border border-gray-300 px-3 py-2 font-mono text-sm focus:ring-2 focus:ring-blue-500 focus:outline-none disabled:bg-gray-50 disabled:text-gray-500"
                  required
                />
              </div>
              <div>
                <label className="mb-1 block text-sm font-medium text-gray-700">
                  数据类型 <span className="text-red-500">*</span>
                </label>
                <select
                  value={field.dataType}
                  onChange={(e) => update('dataType', e.target.value)}
                  className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm focus:ring-2 focus:ring-blue-500 focus:outline-none"
                >
                  {DATA_TYPES.map((t) => (
                    <option key={t} value={t}>
                      {t}
                    </option>
                  ))}
                </select>
              </div>
            </div>
            <div>
              <label className="mb-1 block text-sm font-medium text-gray-700">
                列表达式 <span className="text-red-500">*</span>
              </label>
              <input
                type="text"
                value={field.columnExpr}
                onChange={(e) => update('columnExpr', e.target.value)}
                placeholder="e.g. u.user_name"
                className="w-full rounded-md border border-gray-300 px-3 py-2 font-mono text-sm focus:ring-2 focus:ring-blue-500 focus:outline-none"
                required
              />
            </div>
            <div>
              <label className="mb-1 block text-sm font-medium text-gray-700">显示名称</label>
              <input
                type="text"
                value={field.displayName || ''}
                onChange={(e) => update('displayName', e.target.value)}
                placeholder="e.g. 用户名"
                className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm focus:ring-2 focus:ring-blue-500 focus:outline-none"
              />
            </div>
          </fieldset>

          {/* Section 2: Behavior */}
          <fieldset className="space-y-4">
            <legend className="text-sm font-semibold tracking-wider text-gray-900 uppercase">
              行为配置
            </legend>
            <div>
              <label className="mb-2 block text-sm font-medium text-gray-700">操作符</label>
              <div className="flex flex-wrap gap-1.5">
                {ALL_OPERATORS.map((op) => (
                  <button
                    key={op}
                    type="button"
                    onClick={() => toggleOperator(op)}
                    className={`rounded border px-2 py-1 text-xs transition-colors ${
                      (field.operators || []).includes(op)
                        ? 'border-blue-300 bg-blue-100 text-blue-800'
                        : 'border-gray-300 bg-white text-gray-600 hover:bg-gray-50'
                    }`}
                    title={op}
                  >
                    {OPERATOR_LABELS[op] || op}
                  </button>
                ))}
              </div>
              <p className="mt-1 text-xs text-gray-500">已选 {(field.operators || []).length} 个</p>
            </div>
            <div className="flex gap-6">
              <label className="flex items-center gap-2 text-sm">
                <input
                  type="checkbox"
                  checked={field.sortable || false}
                  onChange={(e) => update('sortable', e.target.checked)}
                  className="rounded border-gray-300 text-blue-600 focus:ring-blue-500"
                />
                可排序
              </label>
              <label className="flex items-center gap-2 text-sm">
                <input
                  type="checkbox"
                  checked={field.searchable || false}
                  onChange={(e) => update('searchable', e.target.checked)}
                  className="rounded border-gray-300 text-blue-600 focus:ring-blue-500"
                />
                可搜索
              </label>
              <label className="flex items-center gap-2 text-sm">
                <input
                  type="checkbox"
                  checked={field.required || false}
                  onChange={(e) => update('required', e.target.checked)}
                  className="rounded border-gray-300 text-blue-600 focus:ring-blue-500"
                />
                必填
              </label>
            </div>
            <div>
              <label className="mb-1 block text-sm font-medium text-gray-700">排序权重</label>
              <input
                type="number"
                value={field.sortOrder ?? 0}
                onChange={(e) => update('sortOrder', parseInt(e.target.value) || 0)}
                className="w-32 rounded-md border border-gray-300 px-3 py-2 text-sm focus:ring-2 focus:ring-blue-500 focus:outline-none"
              />
              <p className="mt-1 text-xs text-gray-500">数值越小越靠前</p>
            </div>
          </fieldset>

          {/* Section 3: UI Hints (collapsible) */}
          <fieldset className="space-y-4">
            <button
              type="button"
              className="flex items-center gap-2 text-sm font-semibold tracking-wider text-gray-900 uppercase"
              onClick={() => setShowUiHints(!showUiHints)}
            >
              <svg
                className={`h-4 w-4 transition-transform ${showUiHints ? 'rotate-90' : ''}`}
                fill="none"
                stroke="currentColor"
                viewBox="0 0 24 24"
              >
                <path
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  strokeWidth={2}
                  d="M9 5l7 7-7 7"
                />
              </svg>
              UI 提示 (高级)
            </button>

            {showUiHints && (
              <div className="space-y-4 border-l-2 border-gray-100 pl-6">
                <div className="grid grid-cols-2 gap-4">
                  <div>
                    <label className="mb-1 block text-sm font-medium text-gray-700">UI 控件</label>
                    <select
                      value={field.uiComponent || ''}
                      onChange={(e) => update('uiComponent', (e.target.value || undefined) as any)}
                      className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm focus:ring-2 focus:ring-blue-500 focus:outline-none"
                    >
                      <option value="">自动</option>
                      {UI_COMPONENTS.map((c) => (
                        <option key={c} value={c}>
                          {c}
                        </option>
                      ))}
                    </select>
                  </div>
                  <div>
                    <label className="mb-1 block text-sm font-medium text-gray-700">字段分组</label>
                    <input
                      type="text"
                      value={field.fieldGroup || ''}
                      onChange={(e) => update('fieldGroup', e.target.value || undefined)}
                      placeholder="e.g. 基本信息"
                      className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm focus:ring-2 focus:ring-blue-500 focus:outline-none"
                    />
                  </div>
                </div>
                <div>
                  <label className="mb-1 block text-sm font-medium text-gray-700">占位文本</label>
                  <input
                    type="text"
                    value={field.placeholder || ''}
                    onChange={(e) => update('placeholder', e.target.value || undefined)}
                    placeholder="e.g. 请输入用户名"
                    className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm focus:ring-2 focus:ring-blue-500 focus:outline-none"
                  />
                </div>
                <div className="grid grid-cols-2 gap-4">
                  <div>
                    <label className="mb-1 block text-sm font-medium text-gray-700">默认值</label>
                    <input
                      type="text"
                      value={field.defaultValue || ''}
                      onChange={(e) => update('defaultValue', e.target.value || undefined)}
                      className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm focus:ring-2 focus:ring-blue-500 focus:outline-none"
                    />
                  </div>
                  <div>
                    <label className="mb-1 block text-sm font-medium text-gray-700">字典编码</label>
                    <input
                      type="text"
                      value={(field as any).dictCode || ''}
                      onChange={(e) =>
                        setField(
                          (prev) => ({ ...prev, dictCode: e.target.value || undefined }) as any,
                        )
                      }
                      placeholder="e.g. sys_status"
                      className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm focus:ring-2 focus:ring-blue-500 focus:outline-none"
                    />
                  </div>
                </div>
                <div>
                  <label className="mb-1 block text-sm font-medium text-gray-700">联动字段</label>
                  <input
                    type="text"
                    value={field.linkedField || ''}
                    onChange={(e) => update('linkedField', e.target.value || undefined)}
                    placeholder="e.g. department_id"
                    className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm focus:ring-2 focus:ring-blue-500 focus:outline-none"
                  />
                  <p className="mt-1 text-xs text-gray-500">用于级联选择的关联字段编码</p>
                </div>
              </div>
            )}
          </fieldset>
        </form>

        {/* Footer */}
        <div className="flex justify-end gap-3 border-t border-gray-200 bg-gray-50 px-6 py-4">
          <button
            type="button"
            onClick={onCancel}
            className="rounded-md border border-gray-300 px-4 py-2 text-sm text-gray-700 hover:bg-gray-50"
          >
            取消
          </button>
          <button
            type="button"
            onClick={() => onSave(field)}
            className="rounded-md bg-blue-600 px-4 py-2 text-sm text-white hover:bg-blue-700 disabled:opacity-50"
            disabled={loading || !field.fieldCode || !field.columnExpr}
          >
            {loading ? '保存中...' : mode === 'add' ? '添加' : '保存'}
          </button>
        </div>
      </div>
    </>
  );
}
