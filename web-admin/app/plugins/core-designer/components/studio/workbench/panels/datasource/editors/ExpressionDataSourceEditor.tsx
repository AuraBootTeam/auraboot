/**
 * Expression DataSource Editor
 *
 * Editor for configuring expression-based data sources.
 *
 * @since 3.2.0
 */

import React, { useCallback, useEffect, useState } from 'react';
import { expressionParser } from '~/plugins/core-designer/components/studio/services/runtime/expression/expression-parser';
import type { ExpressionDataSourceConfig, DataSourceEditorProps } from '../types';

/**
 * Expression DataSource Editor Component
 */
export const ExpressionDataSourceEditor: React.FC<
  DataSourceEditorProps<ExpressionDataSourceConfig>
> = ({ value, onChange, context }) => {
  const [validation, setValidation] = useState<{ valid: boolean; error?: string }>({ valid: true });

  // Validate expression on change
  useEffect(() => {
    if (value.expression) {
      const result = expressionParser.validate(value.expression);
      setValidation(result);

      // Extract dependencies
      if (result.valid) {
        const deps = expressionParser.extractVariables(value.expression);
        if (JSON.stringify(deps) !== JSON.stringify(value.dependencies)) {
          onChange({
            ...value,
            dependencies: deps,
          });
        }
      }
    }
  }, [value.expression]);

  // Handle expression change
  const handleExpressionChange = useCallback(
    (e: React.ChangeEvent<HTMLTextAreaElement>) => {
      onChange({
        ...value,
        expression: e.target.value,
      });
    },
    [value, onChange],
  );

  return (
    <div className="space-y-3">
      {/* Expression Input */}
      <div>
        <label className="mb-1 block text-xs font-medium text-gray-700">数据表达式</label>
        <div className="relative">
          <textarea
            value={value.expression}
            onChange={handleExpressionChange}
            className={`h-24 w-full rounded-md border px-2 py-2 font-mono text-xs focus:ring-1 focus:outline-none ${
              validation.valid
                ? 'border-gray-200 focus:ring-blue-500'
                : 'border-red-300 focus:ring-red-500'
            }`}
            placeholder="{{ context.cities.filter(c => c.provinceCode === form.province) }}"
          />

          {/* fx indicator */}
          <div className="absolute top-2 right-2 font-mono text-[10px] text-gray-400">fx</div>
        </div>

        {/* Validation Error */}
        {!validation.valid && validation.error && (
          <p className="mt-1 text-[10px] text-red-500">{validation.error}</p>
        )}

        {/* Help Text */}
        <p className="mt-1 text-[10px] text-gray-500">
          使用 {'{{ }}'} 包裹表达式，返回值必须是数组
        </p>
      </div>

      {/* Dependencies Display */}
      <div>
        <label className="mb-1 block text-xs font-medium text-gray-700">依赖变量</label>
        {value.dependencies && value.dependencies.length > 0 ? (
          <div className="flex flex-wrap gap-1">
            {value.dependencies.map((dep) => (
              <span
                key={dep}
                className="rounded-full bg-gray-100 px-2 py-0.5 text-[10px] text-gray-600"
              >
                {dep}
              </span>
            ))}
          </div>
        ) : (
          <p className="text-[10px] text-gray-400">自动从表达式中提取</p>
        )}
      </div>

      {/* Example Templates */}
      <div>
        <label className="mb-2 block text-xs font-medium text-gray-700">常用模板</label>
        <div className="space-y-1.5">
          <TemplateButton
            label="过滤数组"
            template="{{ context.items.filter(item => item.type === form.selectedType) }}"
            onClick={(t) => onChange({ ...value, expression: t })}
          />
          <TemplateButton
            label="映射转换"
            template="{{ context.items.map(item => ({ value: item.id, label: item.name })) }}"
            onClick={(t) => onChange({ ...value, expression: t })}
          />
          <TemplateButton
            label="条件数据"
            template="{{ form.showAll ? context.allItems : context.activeItems }}"
            onClick={(t) => onChange({ ...value, expression: t })}
          />
        </div>
      </div>
    </div>
  );
};

/**
 * Template Button Component
 */
interface TemplateButtonProps {
  label: string;
  template: string;
  onClick: (template: string) => void;
}

const TemplateButton: React.FC<TemplateButtonProps> = ({ label, template, onClick }) => (
  <button
    type="button"
    onClick={() => onClick(template)}
    className="w-full rounded border border-gray-200 bg-gray-50 px-2 py-1.5 text-left text-[11px] text-gray-600 transition-colors hover:bg-gray-100"
  >
    <span className="font-medium">{label}</span>
    <span className="mt-0.5 block truncate font-mono text-[10px] text-gray-400">{template}</span>
  </button>
);

export default ExpressionDataSourceEditor;
