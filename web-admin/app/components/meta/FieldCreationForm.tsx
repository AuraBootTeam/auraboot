/**
 * Field Creation Form Component
 * Form for creating a new field and automatically binding it to a model
 */

import { useState, useEffect, useCallback } from 'react';
import { FormulaEditor } from '~/framework/smart/components/formula/FormulaEditor';
import { useDslRegistry } from '~/contexts/DslRegistryContext';

interface FieldCreationFormProps {
  modelPid: string;
  modelCode: string;
  onValidationChange?: (isValid: boolean) => void;
  onChange?: (formData: FieldCreationFormData) => void;
}

export interface FieldCreationFormData {
  code: string;
  dataType: string;
  feature?: {
    required?: boolean;
    unique?: boolean;
    indexed?: boolean;
  };
  /** Expression for COMPUTED fields */
  computedExpression?: string;
  status?: string;
  autoPublish?: boolean;
  modelPid: string;
}

/** @deprecated — kept as fallback; prefer useDslRegistry().getEnumOptions('DataType') */
const DATA_TYPES_FALLBACK = [
  { value: 'string', label: 'String' },
  { value: 'integer', label: 'Integer' },
  { value: 'long', label: 'Long' },
  { value: 'double', label: 'Double' },
  { value: 'boolean', label: 'Boolean' },
  { value: 'date', label: 'Date' },
  { value: 'datetime', label: 'DateTime' },
  { value: 'text', label: 'Text' },
  { value: 'json', label: 'json' },
  { value: 'computed', label: 'Computed' },
  { value: 'ai_text', label: 'AI Text' },
];

export function FieldCreationForm({
  modelPid,
  modelCode,
  onValidationChange,
  onChange,
}: FieldCreationFormProps) {
  const { ensureLoaded, getEnumOptions } = useDslRegistry();
  useEffect(() => { ensureLoaded(); }, [ensureLoaded]);
  const DATA_TYPES =
    getEnumOptions('DataType').length > 0 ? getEnumOptions('DataType') : DATA_TYPES_FALLBACK;

  const [formData, setFormData] = useState<FieldCreationFormData>({
    code: '',
    dataType: 'string',
    feature: {
      required: false,
      unique: false,
      indexed: false,
    },
    status: 'draft',
    autoPublish: false,
    modelPid,
  });

  const [errors, setErrors] = useState<Record<string, string>>({});

  useEffect(() => {
    // Validate form
    const newErrors: Record<string, string> = {};

    // Validate code format
    if (formData.code) {
      if (!/^[a-zA-Z][a-zA-Z0-9_]*$/.test(formData.code)) {
        newErrors.code = '字段编码必须以字母开头，只能包含字母、数字和下划线';
      }
    } else {
      newErrors.code = '字段编码不能为空';
    }

    // Validate data type
    if (!formData.dataType) {
      newErrors.dataType = '数据类型不能为空';
    }

    // Validate computed expression
    if (formData.dataType === 'computed' && !formData.computedExpression?.trim()) {
      newErrors.computedExpression = '计算表达式不能为空';
    }

    setErrors(newErrors);

    const isValid = Object.keys(newErrors).length === 0 && formData.code.length > 0;
    onValidationChange?.(isValid);

    if (isValid) {
      onChange?.(formData);
    }
  }, [formData, onValidationChange, onChange]);

  const handleChange = (field: keyof FieldCreationFormData, value: any) => {
    setFormData((prev) => ({
      ...prev,
      [field]: value,
    }));
  };

  const handleFeatureChange = (
    feature: keyof NonNullable<FieldCreationFormData['feature']>,
    value: boolean,
  ) => {
    setFormData((prev) => ({
      ...prev,
      feature: {
        ...prev.feature,
        [feature]: value,
      },
    }));
  };

  return (
    <div className="space-y-4">
      {/* Field Code */}
      <div>
        <label className="mb-1 block text-sm font-medium text-gray-700">
          字段编码 <span className="text-red-500">*</span>
        </label>
        <input
          type="text"
          value={formData.code}
          onChange={(e) => handleChange('code', e.target.value)}
          placeholder="例如: user_name, email, age"
          className={`w-full rounded-md border px-3 py-2 focus:ring-2 focus:outline-none ${
            errors.code
              ? 'border-red-300 focus:ring-red-500'
              : 'border-gray-300 focus:ring-blue-500'
          }`}
        />
        {errors.code && <p className="mt-1 text-sm text-red-600">{errors.code}</p>}
        <p className="mt-1 text-xs text-gray-500">
          字段编码必须以字母开头，只能包含字母、数字和下划线
        </p>
      </div>

      {/* Data Type */}
      <div>
        <label className="mb-1 block text-sm font-medium text-gray-700">
          数据类型 <span className="text-red-500">*</span>
        </label>
        <select
          value={formData.dataType}
          onChange={(e) => handleChange('dataType', e.target.value)}
          className={`w-full rounded-md border px-3 py-2 focus:ring-2 focus:outline-none ${
            errors.dataType
              ? 'border-red-300 focus:ring-red-500'
              : 'border-gray-300 focus:ring-blue-500'
          }`}
        >
          {DATA_TYPES.map((type) => (
            <option key={type.value} value={type.value}>
              {type.label}
            </option>
          ))}
        </select>
        {errors.dataType && <p className="mt-1 text-sm text-red-600">{errors.dataType}</p>}
      </div>

      {/* Computed Expression (only for COMPUTED type) */}
      {formData.dataType === 'computed' && (
        <div>
          <label className="mb-1 block text-sm font-medium text-gray-700">
            计算表达式 <span className="text-red-500">*</span>
          </label>
          <FormulaEditor
            value={formData.computedExpression || ''}
            onChange={(val) => handleChange('computedExpression', val)}
            placeholder="例如: #IF(#amount > 100, 'High', 'Low')"
          />
          {errors.computedExpression && (
            <p className="mt-1 text-sm text-red-600">{errors.computedExpression}</p>
          )}
        </div>
      )}

      {/* Field Features */}
      <div>
        <label className="mb-2 block text-sm font-medium text-gray-700">字段特性</label>
        <div className="space-y-2">
          <label className="flex items-center">
            <input
              type="checkbox"
              checked={formData.feature?.required || false}
              onChange={(e) => handleFeatureChange('required', e.target.checked)}
              className="rounded border-gray-300 text-blue-600 focus:ring-blue-500"
            />
            <span className="ml-2 text-sm text-gray-700">必填</span>
          </label>

          <label className="flex items-center">
            <input
              type="checkbox"
              checked={formData.feature?.unique || false}
              onChange={(e) => handleFeatureChange('unique', e.target.checked)}
              className="rounded border-gray-300 text-blue-600 focus:ring-blue-500"
            />
            <span className="ml-2 text-sm text-gray-700">唯一</span>
          </label>

          <label className="flex items-center">
            <input
              type="checkbox"
              checked={formData.feature?.indexed || false}
              onChange={(e) => handleFeatureChange('indexed', e.target.checked)}
              className="rounded border-gray-300 text-blue-600 focus:ring-blue-500"
            />
            <span className="ml-2 text-sm text-gray-700">索引</span>
          </label>
        </div>
      </div>

      {/* Auto Publish */}
      <div>
        <label className="flex items-center">
          <input
            type="checkbox"
            checked={formData.autoPublish || false}
            onChange={(e) => handleChange('autoPublish', e.target.checked)}
            className="rounded border-gray-300 text-blue-600 focus:ring-blue-500"
          />
          <span className="ml-2 text-sm text-gray-700">立即发布</span>
        </label>
        <p className="mt-1 text-xs text-gray-500">勾选后字段将直接发布，否则保存为草稿状态</p>
      </div>

      {/* Info Box */}
      <div className="rounded-md border border-blue-200 bg-blue-50 p-3">
        <div className="flex">
          <svg
            className="mr-2 h-5 w-5 text-blue-400"
            fill="none"
            stroke="currentColor"
            viewBox="0 0 24 24"
          >
            <path
              strokeLinecap="round"
              strokeLinejoin="round"
              strokeWidth={2}
              d="M13 16h-1v-4h-1m1-4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z"
            />
          </svg>
          <div className="text-sm text-blue-800">
            <p className="font-medium">提示</p>
            <p className="mt-1">
              创建字段后将自动绑定到模型 <span className="font-mono">{modelCode}</span>
            </p>
          </div>
        </div>
      </div>
    </div>
  );
}
