/**
 * ConditionalFormatPanel — Configure conditional formatting rules for table views.
 *
 * Each rule maps a field + operator + value to a style (background color, text color, bold).
 * Rules are evaluated top-to-bottom; first match wins per row.
 */

import React, { useCallback } from 'react';
import type {
  ViewConfig,
  ConditionalFormatRule,
  ConditionalFormatStyle,
} from '~/framework/smart/types/savedView';
import { CONDITIONAL_FORMAT_PRESETS } from '~/framework/smart/types/savedView';

interface FieldOption {
  code: string;
  name: string;
  dataType?: string;
}

export interface ConditionalFormatPanelProps {
  viewConfig: ViewConfig;
  onChange: (config: ViewConfig) => void;
  fields: FieldOption[];
}

const OPERATORS = [
  { value: 'eq', label: '=' },
  { value: 'ne', label: '≠' },
  { value: 'gt', label: '>' },
  { value: 'gte', label: '≥' },
  { value: 'lt', label: '<' },
  { value: 'lte', label: '≤' },
  { value: 'like', label: 'Contains' },
  { value: 'isNull', label: 'Is Empty' },
  { value: 'isNotNull', label: 'Not Empty' },
];

const NO_VALUE_OPS = new Set(['isNull', 'isNotNull']);

function emptyRule(): ConditionalFormatRule {
  return {
    fieldCode: '',
    operator: 'eq',
    value: '',
    style: { backgroundColor: '#ffebee', textColor: '#b71c1c' },
  };
}

export const ConditionalFormatPanel: React.FC<ConditionalFormatPanelProps> = ({
  viewConfig,
  onChange,
  fields,
}) => {
  const rules = viewConfig.conditionalFormats || [];

  const updateRules = useCallback(
    (newRules: ConditionalFormatRule[]) => {
      onChange({ ...viewConfig, conditionalFormats: newRules });
    },
    [viewConfig, onChange],
  );

  const handleAdd = useCallback(() => {
    const rule = emptyRule();
    if (fields.length > 0) rule.fieldCode = fields[0].code;
    updateRules([...rules, rule]);
  }, [rules, fields, updateRules]);

  const handleRemove = useCallback(
    (index: number) => {
      updateRules(rules.filter((_, i) => i !== index));
    },
    [rules, updateRules],
  );

  const handleUpdate = useCallback(
    (index: number, patch: Partial<ConditionalFormatRule>) => {
      updateRules(rules.map((r, i) => (i === index ? { ...r, ...patch } : r)));
    },
    [rules, updateRules],
  );

  const handleStyleUpdate = useCallback(
    (index: number, stylePatch: Partial<ConditionalFormatStyle>) => {
      const rule = rules[index];
      handleUpdate(index, { style: { ...rule.style, ...stylePatch } });
    },
    [rules, handleUpdate],
  );

  const handlePresetSelect = useCallback(
    (index: number, preset: (typeof CONDITIONAL_FORMAT_PRESETS)[number]) => {
      handleStyleUpdate(index, { backgroundColor: preset.bg, textColor: preset.text });
    },
    [handleStyleUpdate],
  );

  return (
    <div className="space-y-3" data-testid="conditional-format-panel">
      {rules.map((rule, index) => (
        <div
          key={index}
          className="space-y-2 rounded-lg border border-gray-200 bg-white p-3"
          data-testid={`cf-rule-${index}`}
        >
          {/* Row 1: Field + Operator + Value + Remove */}
          <div className="flex items-center gap-2">
            <select
              value={rule.fieldCode}
              onChange={(e) => handleUpdate(index, { fieldCode: e.target.value })}
              className="flex-1 rounded-md border border-gray-300 px-2 py-1.5 text-sm focus:border-blue-500 focus:ring-2 focus:ring-blue-500 focus:outline-none"
              data-testid={`cf-field-${index}`}
            >
              <option value="">Select field...</option>
              {fields.map((f) => (
                <option key={f.code} value={f.code}>
                  {f.name}
                </option>
              ))}
            </select>

            <select
              value={rule.operator}
              onChange={(e) =>
                handleUpdate(index, {
                  operator: e.target.value as ConditionalFormatRule['operator'],
                })
              }
              className="w-28 rounded-md border border-gray-300 px-2 py-1.5 text-sm focus:border-blue-500 focus:ring-2 focus:ring-blue-500 focus:outline-none"
              data-testid={`cf-operator-${index}`}
            >
              {OPERATORS.map((op) => (
                <option key={op.value} value={op.value}>
                  {op.label}
                </option>
              ))}
            </select>

            {!NO_VALUE_OPS.has(rule.operator) && (
              <input
                type="text"
                value={rule.value || ''}
                onChange={(e) => handleUpdate(index, { value: e.target.value })}
                placeholder="Value"
                className="w-28 rounded-md border border-gray-300 px-2 py-1.5 text-sm focus:border-blue-500 focus:ring-2 focus:ring-blue-500 focus:outline-none"
                data-testid={`cf-value-${index}`}
              />
            )}

            <button
              type="button"
              onClick={() => handleRemove(index)}
              className="flex-shrink-0 rounded p-1 text-gray-400 hover:bg-red-50 hover:text-red-500"
              title="Remove rule"
              data-testid={`cf-remove-${index}`}
            >
              <svg className="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  strokeWidth={2}
                  d="M6 18L18 6M6 6l12 12"
                />
              </svg>
            </button>
          </div>

          {/* Row 2: Color presets + Preview */}
          <div className="flex items-center gap-2">
            <span className="w-10 flex-shrink-0 text-xs text-gray-500">Style:</span>
            <div className="flex gap-1">
              {CONDITIONAL_FORMAT_PRESETS.map((preset) => (
                <button
                  key={preset.label}
                  type="button"
                  onClick={() => handlePresetSelect(index, preset)}
                  title={preset.label}
                  className={`h-5 w-5 rounded-full border transition-all ${
                    rule.style.backgroundColor === preset.bg
                      ? 'border-gray-600 ring-2 ring-blue-400 ring-offset-1'
                      : 'border-gray-300 hover:border-gray-500'
                  }`}
                  style={{ backgroundColor: preset.bg }}
                  data-testid={`cf-preset-${index}-${preset.label.toLowerCase()}`}
                />
              ))}
            </div>

            {/* Bold toggle */}
            <button
              type="button"
              onClick={() => handleStyleUpdate(index, { bold: !rule.style.bold })}
              className={`ml-2 rounded px-1.5 py-0.5 text-xs font-bold transition-colors ${
                rule.style.bold
                  ? 'bg-gray-200 text-gray-800'
                  : 'text-gray-400 hover:bg-gray-100 hover:text-gray-600'
              }`}
              title="Bold"
              data-testid={`cf-bold-${index}`}
            >
              B
            </button>

            {/* Preview swatch */}
            <div
              className="ml-auto flex h-6 items-center rounded px-2 text-xs"
              style={{
                backgroundColor: rule.style.backgroundColor || '#f5f5f5',
                color: rule.style.textColor || '#424242',
                fontWeight: rule.style.bold ? 700 : 400,
              }}
              data-testid={`cf-preview-${index}`}
            >
              Preview
            </div>
          </div>
        </div>
      ))}

      {/* Add rule button */}
      <button
        type="button"
        onClick={handleAdd}
        className="w-full rounded-md border border-dashed border-gray-300 py-1.5 text-sm text-blue-600 transition-colors hover:border-blue-400 hover:text-blue-700"
        data-testid="cf-add-rule"
      >
        + Add Formatting Rule
      </button>
    </div>
  );
};

export default ConditionalFormatPanel;
