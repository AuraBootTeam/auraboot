/**
 * Computed Field Editor Component
 *
 * Editor for creating and editing computed fields.
 *
 * @since 3.2.0
 */

import React, { useState, useCallback, useMemo } from 'react';
import {
  COMPUTED_TYPES,
  RETURN_TYPES,
  getComputedTypeInfo,
  type ComputedFieldDefinition,
  type ExpressionTestResult,
} from './types';

interface ComputedFieldEditorProps {
  /** Field definition to edit */
  field?: ComputedFieldDefinition;
  /** Available fields for expression */
  availableFields?: Array<{ path: string; label: string; type: string }>;
  /** On save */
  onSave?: (field: ComputedFieldDefinition) => void;
  /** On cancel */
  onCancel?: () => void;
  /** On test expression */
  onTestExpression?: (expression: string) => Promise<ExpressionTestResult>;
}

/**
 * Computed Field Editor Component
 */
export const ComputedFieldEditor: React.FC<ComputedFieldEditorProps> = ({
  field,
  availableFields = [],
  onSave,
  onCancel,
  onTestExpression,
}) => {
  const [formData, setFormData] = useState<ComputedFieldDefinition>({
    code: field?.code || '',
    label: field?.label || '',
    expression: field?.expression || '',
    virtualType: field?.virtualType || 'computed_readonly',
    returnType: field?.returnType || 'string',
    description: field?.description || '',
    enabled: field?.enabled ?? true,
  });

  const [testResult, setTestResult] = useState<ExpressionTestResult | null>(null);
  const [isTesting, setIsTesting] = useState(false);
  const [errors, setErrors] = useState<Record<string, string>>({});

  const isEditing = !!field;

  // Parse dependencies from expression
  const dependencies = useMemo(() => {
    const deps: string[] = [];
    const regex = /#(\w+(?:\.\w+)*)/g;
    let match;
    while ((match = regex.exec(formData.expression)) !== null) {
      if (!deps.includes(match[1])) {
        deps.push(match[1]);
      }
    }
    return deps;
  }, [formData.expression]);

  // Handle field change
  const handleChange = useCallback((key: keyof ComputedFieldDefinition, value: unknown) => {
    setFormData((prev) => ({ ...prev, [key]: value }));
    setErrors((prev) => ({ ...prev, [key]: '' }));
  }, []);

  // Validate form
  const validate = useCallback((): boolean => {
    const newErrors: Record<string, string> = {};

    if (!formData.code.trim()) {
      newErrors.code = '请输入字段编码';
    } else if (!/^[a-zA-Z][a-zA-Z0-9_]*$/.test(formData.code)) {
      newErrors.code = '编码必须以字母开头，只能包含字母、数字和下划线';
    }

    if (!formData.label.trim()) {
      newErrors.label = '请输入字段名称';
    }

    if (!formData.expression.trim()) {
      newErrors.expression = '请输入计算表达式';
    }

    setErrors(newErrors);
    return Object.keys(newErrors).length === 0;
  }, [formData]);

  // Handle save
  const handleSave = useCallback(() => {
    if (!validate()) return;

    onSave?.({
      ...formData,
      dependencies,
    });
  }, [formData, dependencies, validate, onSave]);

  // Handle test
  const handleTest = useCallback(async () => {
    if (!formData.expression.trim()) return;

    setIsTesting(true);
    setTestResult(null);

    try {
      if (onTestExpression) {
        const result = await onTestExpression(formData.expression);
        setTestResult(result);
      } else {
        // Simple local evaluation
        setTestResult({
          success: true,
          result: '(模拟结果)',
          executionTime: 1,
        });
      }
    } catch (error) {
      setTestResult({
        success: false,
        error: error instanceof Error ? error.message : '测试失败',
      });
    } finally {
      setIsTesting(false);
    }
  }, [formData.expression, onTestExpression]);

  // Insert field reference
  const insertFieldRef = useCallback((fieldPath: string) => {
    setFormData((prev) => ({
      ...prev,
      expression: prev.expression + `#${fieldPath}`,
    }));
  }, []);

  const typeInfo = getComputedTypeInfo(formData.virtualType);

  return (
    <div className="flex h-full flex-col bg-white">
      {/* Header */}
      <div className="flex-shrink-0 border-b border-gray-200 px-4 py-3">
        <h3 className="text-sm font-semibold text-gray-900">
          {isEditing ? '编辑计算字段' : '创建计算字段'}
        </h3>
      </div>

      {/* Content */}
      <div className="flex-1 space-y-4 overflow-y-auto p-4">
        {/* Basic info */}
        <div className="grid grid-cols-2 gap-4">
          {/* Code */}
          <div>
            <label className="mb-1 block text-xs font-medium text-gray-700">字段编码 *</label>
            <input
              type="text"
              value={formData.code}
              onChange={(e) => handleChange('code', e.target.value)}
              disabled={isEditing}
              className={`w-full rounded-md border px-3 py-2 text-sm ${errors.code ? 'border-red-300' : 'border-gray-200'} ${isEditing ? 'bg-gray-50' : ''} `}
              placeholder="例如: totalAmount"
            />
            {errors.code && <p className="mt-1 text-xs text-red-500">{errors.code}</p>}
          </div>

          {/* Label */}
          <div>
            <label className="mb-1 block text-xs font-medium text-gray-700">字段名称 *</label>
            <input
              type="text"
              value={formData.label}
              onChange={(e) => handleChange('label', e.target.value)}
              className={`w-full rounded-md border px-3 py-2 text-sm ${errors.label ? 'border-red-300' : 'border-gray-200'} `}
              placeholder="例如: 总金额"
            />
            {errors.label && <p className="mt-1 text-xs text-red-500">{errors.label}</p>}
          </div>
        </div>

        {/* Computed type */}
        <div>
          <label className="mb-2 block text-xs font-medium text-gray-700">计算类型</label>
          <div className="grid grid-cols-3 gap-2">
            {COMPUTED_TYPES.map((type) => (
              <button
                key={type.type}
                type="button"
                onClick={() => handleChange('virtualType', type.type)}
                className={`rounded-lg border-2 p-3 text-left transition-all ${
                  formData.virtualType === type.type
                    ? 'border-blue-500 bg-blue-50'
                    : 'border-gray-200 hover:border-gray-300'
                } `}
              >
                <div className="mb-1 flex items-center gap-2">
                  <span className="text-lg">{type.icon}</span>
                  <span className="text-xs font-medium">{type.label}</span>
                </div>
                <p className="line-clamp-2 text-[10px] text-gray-500">{type.description}</p>
              </button>
            ))}
          </div>
          <div className="mt-2 flex items-center gap-2 text-xs text-gray-500">
            <span className={typeInfo.persisted ? 'text-green-600' : 'text-gray-400'}>
              {typeInfo.persisted ? '✓ 持久化' : '○ 不持久化'}
            </span>
          </div>
        </div>

        {/* Return type */}
        <div>
          <label className="mb-2 block text-xs font-medium text-gray-700">返回类型</label>
          <div className="flex flex-wrap gap-2">
            {RETURN_TYPES.map((type) => (
              <button
                key={type.value}
                type="button"
                onClick={() => handleChange('returnType', type.value)}
                className={`rounded-md border px-3 py-1.5 text-xs transition-colors ${
                  formData.returnType === type.value
                    ? 'border-blue-500 bg-blue-50 text-blue-700'
                    : 'border-gray-200 hover:border-gray-300'
                } `}
              >
                <span className="mr-1 opacity-50">{type.icon}</span>
                {type.label}
              </button>
            ))}
          </div>
        </div>

        {/* Expression */}
        <div>
          <div className="mb-2 flex items-center justify-between">
            <label className="text-xs font-medium text-gray-700">计算表达式 *</label>
            <button
              type="button"
              onClick={handleTest}
              disabled={isTesting || !formData.expression.trim()}
              className={`rounded px-2 py-1 text-xs ${
                isTesting || !formData.expression.trim()
                  ? 'cursor-not-allowed text-gray-400'
                  : 'text-blue-600 hover:bg-blue-50'
              } `}
            >
              {isTesting ? '测试中...' : '测试表达式'}
            </button>
          </div>
          <textarea
            value={formData.expression}
            onChange={(e) => handleChange('expression', e.target.value)}
            className={`w-full resize-none rounded-md border px-3 py-2 font-mono text-sm ${errors.expression ? 'border-red-300' : 'border-gray-200'} `}
            rows={4}
            placeholder="#quantity * #unitPrice"
          />
          {errors.expression && <p className="mt-1 text-xs text-red-500">{errors.expression}</p>}

          {/* Test result */}
          {testResult && (
            <div
              className={`mt-2 rounded p-2 text-xs ${
                testResult.success ? 'bg-green-50 text-green-700' : 'bg-red-50 text-red-700'
              }`}
            >
              {testResult.success ? (
                <>
                  <strong>结果:</strong> {String(testResult.result)}
                  {testResult.executionTime && (
                    <span className="ml-2 text-gray-500">({testResult.executionTime}ms)</span>
                  )}
                </>
              ) : (
                <>
                  <strong>错误:</strong> {testResult.error}
                </>
              )}
            </div>
          )}
        </div>

        {/* Available fields */}
        {availableFields.length > 0 && (
          <div>
            <label className="mb-2 block text-xs font-medium text-gray-700">可用字段</label>
            <div className="max-h-32 overflow-y-auto rounded-md border border-gray-200 p-2">
              <div className="flex flex-wrap gap-1">
                {availableFields.map((field) => (
                  <button
                    key={field.path}
                    type="button"
                    onClick={() => insertFieldRef(field.path)}
                    className="rounded bg-gray-100 px-2 py-1 text-xs transition-colors hover:bg-gray-200"
                    title={`${field.label} (${field.type})`}
                  >
                    #{field.path}
                  </button>
                ))}
              </div>
            </div>
          </div>
        )}

        {/* Dependencies */}
        {dependencies.length > 0 && (
          <div>
            <label className="mb-1 block text-xs font-medium text-gray-700">依赖字段</label>
            <div className="flex flex-wrap gap-1">
              {dependencies.map((dep) => (
                <span key={dep} className="rounded bg-blue-100 px-2 py-1 text-xs text-blue-700">
                  {dep}
                </span>
              ))}
            </div>
          </div>
        )}

        {/* Description */}
        <div>
          <label className="mb-1 block text-xs font-medium text-gray-700">描述 (可选)</label>
          <textarea
            value={formData.description}
            onChange={(e) => handleChange('description', e.target.value)}
            className="w-full resize-none rounded-md border border-gray-200 px-3 py-2 text-sm"
            rows={2}
            placeholder="字段用途说明..."
          />
        </div>

        {/* Enabled toggle */}
        <div className="flex items-center gap-2">
          <input
            type="checkbox"
            id="enabled"
            checked={formData.enabled}
            onChange={(e) => handleChange('enabled', e.target.checked)}
            className="h-4 w-4 text-blue-600"
          />
          <label htmlFor="enabled" className="text-xs text-gray-700">
            启用此计算字段
          </label>
        </div>
      </div>

      {/* Footer */}
      <div className="flex-shrink-0 border-t border-gray-200 bg-gray-50 px-4 py-3">
        <div className="flex justify-end gap-2">
          <button
            type="button"
            onClick={onCancel}
            className="rounded-md px-4 py-2 text-sm text-gray-600 hover:bg-gray-100"
          >
            取消
          </button>
          <button
            type="button"
            onClick={handleSave}
            className="rounded-md bg-blue-500 px-4 py-2 text-sm text-white hover:bg-blue-600"
          >
            {isEditing ? '保存' : '创建'}
          </button>
        </div>
      </div>
    </div>
  );
};

export default ComputedFieldEditor;
