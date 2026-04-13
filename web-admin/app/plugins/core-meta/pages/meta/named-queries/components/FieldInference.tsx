/**
 * Field auto-inference from SQL test results.
 * Runs a 1-row test query, extracts column names, infers types, and batch-adds fields.
 */

import React, { useState, useCallback } from 'react';
import { namedQueryService } from '~/services/namedQueryService';
import type { NamedQueryFieldDTO, NamedQueryFieldRequest } from '~/services/namedQueryService';
import { OPERATORS_BY_TYPE } from './constants';

interface InferredField {
  fieldCode: string;
  columnExpr: string;
  dataType: string;
  sampleValue: string;
  selected: boolean;
  existing: boolean; // already configured
}

interface FieldInferenceProps {
  pid: string;
  queryCode: string;
  existingFields: NamedQueryFieldDTO[];
  onFieldsAdded: (fields: NamedQueryFieldDTO[]) => void;
  showToast: (msg: string, type: 'success' | 'error') => void;
}

const DATE_REGEX = /^\d{4}-\d{2}-\d{2}/;

function inferType(value: unknown): string {
  if (value == null) return 'string';
  if (typeof value === 'number') return 'number';
  if (typeof value === 'boolean') return 'boolean';
  if (typeof value === 'string' && DATE_REGEX.test(value)) return 'date';
  if (Array.isArray(value)) return 'array';
  if (typeof value === 'object') return 'json';
  return 'string';
}

export default function FieldInference({
  pid,
  queryCode,
  existingFields,
  onFieldsAdded,
  showToast,
}: FieldInferenceProps) {
  const [inferredFields, setInferredFields] = useState<InferredField[]>([]);
  const [inferring, setInferring] = useState(false);
  const [saving, setSaving] = useState(false);
  const [showPanel, setShowPanel] = useState(false);

  const existingCodes = new Set(existingFields.map((f) => f.fieldCode));

  const handleInfer = useCallback(async () => {
    setInferring(true);
    setShowPanel(true);
    try {
      const result = await namedQueryService.testQuery(pid, { pageNum: 1, pageSize: 1 });
      const rows = result.sampleData || [];
      if (rows.length === 0) {
        showToast('查询无结果，无法推断字段', 'error');
        setInferredFields([]);
        return;
      }

      const firstRow = rows[0];
      const inferred: InferredField[] = Object.entries(firstRow).map(([key, value]) => ({
        fieldCode: key,
        columnExpr: key,
        dataType: inferType(value),
        sampleValue: value != null ? String(value).substring(0, 80) : '(null)',
        selected: !existingCodes.has(key),
        existing: existingCodes.has(key),
      }));

      setInferredFields(inferred);
      showToast(`检测到 ${inferred.length} 个字段`, 'success');
    } catch (error: any) {
      showToast(error.message || '字段推断失败', 'error');
    } finally {
      setInferring(false);
    }
  }, [pid, existingCodes, showToast]);

  const handleToggle = (fieldCode: string) => {
    setInferredFields((prev) =>
      prev.map((f) =>
        f.fieldCode === fieldCode && !f.existing ? { ...f, selected: !f.selected } : f,
      ),
    );
  };

  const handleToggleAll = () => {
    const selectableFields = inferredFields.filter((f) => !f.existing);
    const allSelected = selectableFields.every((f) => f.selected);
    setInferredFields((prev) =>
      prev.map((f) => (f.existing ? f : { ...f, selected: !allSelected })),
    );
  };

  const handleBatchAdd = useCallback(async () => {
    const selected = inferredFields.filter((f) => f.selected && !f.existing);
    if (selected.length === 0) {
      showToast('请选择要添加的字段', 'error');
      return;
    }

    const requests: NamedQueryFieldRequest[] = selected.map((f) => ({
      fieldCode: f.fieldCode,
      columnExpr: f.columnExpr,
      dataType: f.dataType,
      operators: OPERATORS_BY_TYPE[f.dataType] || ['eq'],
      sortable: false,
      searchable: true,
    }));

    setSaving(true);
    try {
      const added = await namedQueryService.batchSaveFields(queryCode, requests);
      onFieldsAdded(added);
      showToast(`成功添加 ${added.length} 个字段`, 'success');
      setShowPanel(false);
      setInferredFields([]);
    } catch (error: any) {
      showToast(error.message || '批量添加失败', 'error');
    } finally {
      setSaving(false);
    }
  }, [inferredFields, queryCode, onFieldsAdded, showToast]);

  const selectedCount = inferredFields.filter((f) => f.selected && !f.existing).length;

  return (
    <div>
      <button
        type="button"
        onClick={handleInfer}
        disabled={inferring}
        className="inline-flex items-center rounded-md border border-green-300 px-3 py-1.5 text-sm text-green-700 hover:bg-green-50 disabled:opacity-50"
      >
        <svg
          className="mr-1.5 -ml-0.5 h-4 w-4"
          fill="none"
          stroke="currentColor"
          viewBox="0 0 24 24"
        >
          <path
            strokeLinecap="round"
            strokeLinejoin="round"
            strokeWidth={2}
            d="M13 10V3L4 14h7v7l9-11h-7z"
          />
        </svg>
        {inferring ? '检测中...' : '自动检测字段'}
      </button>

      {showPanel && inferredFields.length > 0 && (
        <div className="mt-4 rounded-md border border-gray-200">
          <div className="flex items-center justify-between border-b border-gray-200 bg-gray-50 px-4 py-3">
            <div className="text-sm font-medium text-gray-900">
              检测到 {inferredFields.length} 个字段，已选 {selectedCount} 个
            </div>
            <div className="flex gap-2">
              <button
                type="button"
                onClick={handleToggleAll}
                className="text-sm text-blue-600 hover:text-blue-900"
              >
                {inferredFields.filter((f) => !f.existing).every((f) => f.selected)
                  ? '取消全选'
                  : '全选'}
              </button>
              <button
                type="button"
                onClick={() => {
                  setShowPanel(false);
                  setInferredFields([]);
                }}
                className="text-sm text-gray-500 hover:text-gray-700"
              >
                关闭
              </button>
            </div>
          </div>
          <div className="max-h-64 overflow-y-auto">
            <table className="min-w-full divide-y divide-gray-200">
              <thead className="sticky top-0 bg-gray-50">
                <tr>
                  <th className="w-10 px-4 py-2 text-left text-xs font-medium text-gray-500"></th>
                  <th className="px-4 py-2 text-left text-xs font-medium text-gray-500">字段名</th>
                  <th className="px-4 py-2 text-left text-xs font-medium text-gray-500">
                    推断类型
                  </th>
                  <th className="px-4 py-2 text-left text-xs font-medium text-gray-500">样本值</th>
                  <th className="px-4 py-2 text-left text-xs font-medium text-gray-500">状态</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-200">
                {inferredFields.map((f) => (
                  <tr
                    key={f.fieldCode}
                    className={
                      f.existing ? 'bg-gray-50 opacity-60' : 'cursor-pointer hover:bg-blue-50'
                    }
                    onClick={() => handleToggle(f.fieldCode)}
                  >
                    <td className="px-4 py-2">
                      <input
                        type="checkbox"
                        checked={f.selected}
                        disabled={f.existing}
                        onChange={() => handleToggle(f.fieldCode)}
                        className="rounded border-gray-300 text-blue-600 focus:ring-blue-500"
                      />
                    </td>
                    <td className="px-4 py-2 font-mono text-sm text-gray-900">{f.fieldCode}</td>
                    <td className="px-4 py-2 text-sm">
                      <span className="inline-flex rounded bg-gray-100 px-2 py-0.5 text-xs font-medium text-gray-800">
                        {f.dataType}
                      </span>
                    </td>
                    <td className="max-w-xs truncate px-4 py-2 font-mono text-sm text-gray-500">
                      {f.sampleValue}
                    </td>
                    <td className="px-4 py-2 text-sm">
                      {f.existing ? (
                        <span className="rounded bg-green-50 px-2 py-0.5 text-xs text-green-600">
                          已配置
                        </span>
                      ) : (
                        <span className="text-xs text-blue-600">新增</span>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          {selectedCount > 0 && (
            <div className="flex justify-end border-t border-gray-200 bg-gray-50 px-4 py-3">
              <button
                type="button"
                onClick={handleBatchAdd}
                disabled={saving}
                className="rounded-md bg-green-600 px-4 py-2 text-sm text-white hover:bg-green-700 disabled:opacity-50"
              >
                {saving ? '添加中...' : `批量添加 ${selectedCount} 个字段`}
              </button>
            </div>
          )}
        </div>
      )}
    </div>
  );
}
