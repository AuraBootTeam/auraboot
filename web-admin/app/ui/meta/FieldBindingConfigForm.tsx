/**
 * Field Binding Configuration Form Component
 * Form for configuring field binding options
 */

import React, { useState, useEffect } from 'react';
import type { MetaFieldDTO, FieldBindingRequest } from '~/types/fieldLibrary';
import { FormulaEditor } from '~/framework/smart/components/formula/FormulaEditor';

interface FieldBindingConfigFormProps {
  field: MetaFieldDTO;
  initialConfig?: Partial<FieldBindingRequest>;
  onChange: (config: FieldBindingRequest) => void;
  onValidationChange?: (isValid: boolean) => void;
}

export function FieldBindingConfigForm({
  field,
  initialConfig,
  onChange,
  onValidationChange,
}: FieldBindingConfigFormProps) {
  const [config, setConfig] = useState<FieldBindingRequest>({
    fieldPid: field.pid,
    aliasCode: initialConfig?.aliasCode || '',
    required: initialConfig?.required ?? false,
    nullable: initialConfig?.nullable ?? true,
    readonly: initialConfig?.readonly ?? false,
    visible: initialConfig?.visible ?? true,
    editable: initialConfig?.editable ?? true,
    defaultValue: initialConfig?.defaultValue || '',
    dictOverrideCode: initialConfig?.dictOverrideCode || '',
    uiHint: initialConfig?.uiHint || '',
    validationOverride: initialConfig?.validationOverride || '',
    displayConfig: initialConfig?.displayConfig || '',
    remarks: initialConfig?.remarks || '',
  });

  const [defaultValueMode, setDefaultValueMode] = useState<'static' | 'expression'>(
    initialConfig?.defaultValue?.startsWith?.('${') ? 'expression' : 'static',
  );
  const [defaultExpression, setDefaultExpression] = useState(
    initialConfig?.defaultValue?.startsWith?.('${') ? initialConfig.defaultValue : '',
  );
  const [errors, setErrors] = useState<Record<string, string>>({});

  useEffect(() => {
    // Validate and notify parent
    const newErrors: Record<string, string> = {};

    // Validate alias code format (optional, but if provided must be valid)
    if (config.aliasCode && !/^[a-zA-Z_][a-zA-Z0-9_]*$/.test(config.aliasCode)) {
      newErrors.aliasCode = '别名只能包含字母、数字和下划线，且必须以字母或下划线开头';
    }

    // Validate JSON fields
    if (config.validationOverride) {
      try {
        JSON.parse(config.validationOverride);
      } catch {
        newErrors.validationOverride = '必须是有效的 JSON 格式';
      }
    }

    if (config.displayConfig) {
      try {
        JSON.parse(config.displayConfig);
      } catch {
        newErrors.displayConfig = '必须是有效的 JSON 格式';
      }
    }

    setErrors(newErrors);

    const isValid = Object.keys(newErrors).length === 0;
    onValidationChange?.(isValid);

    if (isValid) {
      onChange(config);
    }
  }, [config, onChange, onValidationChange]);

  const handleChange = (field: keyof FieldBindingRequest, value: any) => {
    setConfig((prev) => ({
      ...prev,
      [field]: value,
    }));
  };

  return (
    <div className="space-y-4">
      {/* Alias Code */}
      <div>
        <label className="mb-1 block text-sm font-medium text-gray-700">别名 (可选)</label>
        <input
          type="text"
          value={config.aliasCode}
          onChange={(e) => handleChange('aliasCode', e.target.value)}
          placeholder="在此模型中的别名"
          className={`w-full rounded-md border px-3 py-2 focus:ring-2 focus:outline-none ${
            errors.aliasCode
              ? 'border-red-300 focus:ring-red-500'
              : 'border-gray-300 focus:ring-blue-500'
          }`}
        />
        {errors.aliasCode && <p className="mt-1 text-sm text-red-600">{errors.aliasCode}</p>}
        <p className="mt-1 text-xs text-gray-500">为字段在此模型中指定一个特定的名称</p>
      </div>

      {/* Flags */}
      <div className="space-y-3">
        <label className="block text-sm font-medium text-gray-700">字段属性</label>

        <div className="space-y-2">
          <label className="flex items-center">
            <input
              type="checkbox"
              checked={config.required}
              onChange={(e) => handleChange('required', e.target.checked)}
              className="rounded border-gray-300 text-blue-600 focus:ring-blue-500"
            />
            <span className="ml-2 text-sm text-gray-700">必填</span>
          </label>

          <label className="flex items-center">
            <input
              type="checkbox"
              checked={config.nullable}
              onChange={(e) => handleChange('nullable', e.target.checked)}
              className="rounded border-gray-300 text-blue-600 focus:ring-blue-500"
            />
            <span className="ml-2 text-sm text-gray-700">可为空</span>
          </label>

          <label className="flex items-center">
            <input
              type="checkbox"
              checked={config.readonly}
              onChange={(e) => handleChange('readonly', e.target.checked)}
              className="rounded border-gray-300 text-blue-600 focus:ring-blue-500"
            />
            <span className="ml-2 text-sm text-gray-700">只读</span>
          </label>

          <label className="flex items-center">
            <input
              type="checkbox"
              checked={config.visible}
              onChange={(e) => handleChange('visible', e.target.checked)}
              className="rounded border-gray-300 text-blue-600 focus:ring-blue-500"
            />
            <span className="ml-2 text-sm text-gray-700">可见</span>
          </label>

          <label className="flex items-center">
            <input
              type="checkbox"
              checked={config.editable}
              onChange={(e) => handleChange('editable', e.target.checked)}
              className="rounded border-gray-300 text-blue-600 focus:ring-blue-500"
            />
            <span className="ml-2 text-sm text-gray-700">可编辑</span>
          </label>
        </div>
      </div>

      {/* Default Value */}
      <div>
        <div className="mb-1 flex items-center justify-between">
          <label className="block text-sm font-medium text-gray-700">默认值 (可选)</label>
          <div className="flex overflow-hidden rounded-md border border-gray-300">
            <button
              type="button"
              onClick={() => setDefaultValueMode('static')}
              className={`px-2.5 py-1 text-xs transition-colors ${
                defaultValueMode === 'static'
                  ? 'bg-blue-500 text-white'
                  : 'bg-white text-gray-600 hover:bg-gray-50'
              }`}
            >
              静态值
            </button>
            <button
              type="button"
              onClick={() => setDefaultValueMode('expression')}
              className={`border-l px-2.5 py-1 text-xs transition-colors ${
                defaultValueMode === 'expression'
                  ? 'bg-blue-500 text-white'
                  : 'bg-white text-gray-600 hover:bg-gray-50'
              }`}
            >
              表达式
            </button>
          </div>
        </div>
        {defaultValueMode === 'static' ? (
          <input
            type="text"
            value={config.defaultValue}
            onChange={(e) => handleChange('defaultValue', e.target.value)}
            placeholder="字段的默认值"
            className="w-full rounded-md border border-gray-300 px-3 py-2 focus:ring-2 focus:ring-blue-500 focus:outline-none"
          />
        ) : (
          <FormulaEditor
            value={defaultExpression}
            onChange={(val) => {
              setDefaultExpression(val);
              handleChange('defaultValue', val);
            }}
            placeholder="例如: #NOW() 或 #currentUser"
          />
        )}
      </div>

      {/* Dictionary Override */}
      <div>
        <label className="mb-1 block text-sm font-medium text-gray-700">字典覆盖 (可选)</label>
        <input
          type="text"
          value={config.dictOverrideCode}
          onChange={(e) => handleChange('dictOverrideCode', e.target.value)}
          placeholder="覆盖字段的字典绑定"
          className="w-full rounded-md border border-gray-300 px-3 py-2 focus:ring-2 focus:ring-blue-500 focus:outline-none"
        />
        <p className="mt-1 text-xs text-gray-500">指定一个不同的字典代码来覆盖字段的默认字典</p>
      </div>

      {/* UI Hint */}
      <div>
        <label className="mb-1 block text-sm font-medium text-gray-700">UI 提示 (可选)</label>
        <input
          type="text"
          value={config.uiHint}
          onChange={(e) => handleChange('uiHint', e.target.value)}
          placeholder="例如: text, textarea, select"
          className="w-full rounded-md border border-gray-300 px-3 py-2 focus:ring-2 focus:ring-blue-500 focus:outline-none"
        />
        <p className="mt-1 text-xs text-gray-500">指定 UI 组件类型的提示</p>
      </div>

      {/* Validation Override */}
      <div>
        <label className="mb-1 block text-sm font-medium text-gray-700">
          验证规则覆盖 (可选, JSON)
        </label>
        <textarea
          value={config.validationOverride}
          onChange={(e) => handleChange('validationOverride', e.target.value)}
          placeholder='{"maxLength": 100, "pattern": "^[A-Z]"}'
          rows={3}
          className={`w-full rounded-md border px-3 py-2 font-mono text-sm focus:ring-2 focus:outline-none ${
            errors.validationOverride
              ? 'border-red-300 focus:ring-red-500'
              : 'border-gray-300 focus:ring-blue-500'
          }`}
        />
        {errors.validationOverride && (
          <p className="mt-1 text-sm text-red-600">{errors.validationOverride}</p>
        )}
        <p className="mt-1 text-xs text-gray-500">覆盖字段的验证规则 (JSON 格式)</p>
      </div>

      {/* Display Config */}
      <div>
        <label className="mb-1 block text-sm font-medium text-gray-700">
          显示配置 (可选, JSON)
        </label>
        <textarea
          value={config.displayConfig}
          onChange={(e) => handleChange('displayConfig', e.target.value)}
          placeholder='{"width": "100%", "placeholder": "请输入..."}'
          rows={3}
          className={`w-full rounded-md border px-3 py-2 font-mono text-sm focus:ring-2 focus:outline-none ${
            errors.displayConfig
              ? 'border-red-300 focus:ring-red-500'
              : 'border-gray-300 focus:ring-blue-500'
          }`}
        />
        {errors.displayConfig && (
          <p className="mt-1 text-sm text-red-600">{errors.displayConfig}</p>
        )}
        <p className="mt-1 text-xs text-gray-500">自定义显示配置 (JSON 格式)</p>
      </div>

      {/* Remarks */}
      <div>
        <label className="mb-1 block text-sm font-medium text-gray-700">备注 (可选)</label>
        <textarea
          value={config.remarks}
          onChange={(e) => handleChange('remarks', e.target.value)}
          placeholder="关于此绑定的备注信息"
          rows={2}
          className="w-full rounded-md border border-gray-300 px-3 py-2 focus:ring-2 focus:ring-blue-500 focus:outline-none"
        />
      </div>
    </div>
  );
}
