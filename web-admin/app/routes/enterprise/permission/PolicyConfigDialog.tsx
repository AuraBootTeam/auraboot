/**
 * PolicyConfigDialog — Dialog for configuring parameterized permission policies.
 *
 * Renders a dynamic form driven by policySchema JSON. Each field's component
 * is determined by its declared type:
 *   - number  → number input
 *   - string  → text input
 *   - boolean → toggle switch
 *   - enum    → select dropdown
 *   - enum[]  → multi-select checkboxes
 */

import React, { useState, useEffect, useCallback } from 'react';
import { Dialog, DialogContent, DialogHeader, DialogTitle } from '~/ui/ui/dialog';
import { useI18n } from '~/contexts/I18nContext';
import { useToastContext } from '~/contexts/ToastContext';
import { permissionService } from '~/shared/services/permissionService';
import {
  DecisionRuleBindingBlock,
  type DecisionOption,
  type RuleBindingDecisionApi,
  type RuleConsumerBindingDraft,
} from '~/ui/smart/decision/DecisionRuleBindingBlock';
import type { FieldOption } from '~/shared/decision/ui/ConditionBuilder';

// ---------------------------------------------------------------------------
// Policy schema field types
// ---------------------------------------------------------------------------

interface PolicyFieldSchema {
  type: 'number' | 'string' | 'boolean' | 'enum' | 'enum[]' | 'rule-center';
  label: string;
  default?: any;
  required?: boolean;
  min?: number;
  max?: number;
  options?: string[];
  mode?: 'condition' | 'decision' | 'combined';
  expectedMatched?: boolean;
  timeoutMs?: number;
  decisions?: DecisionOption[];
  fields?: FieldOption[];
  fieldCatalogMode?: 'disabled' | 'fallback' | 'merge';
  fieldCatalogModelCode?: string;
  fieldCatalogModelCodeField?: string;
  initialContextJson?: string;
  initialDecisionCode?: string;
}

// ---------------------------------------------------------------------------
// Props
// ---------------------------------------------------------------------------

export interface PolicyConfigDialogProps {
  open: boolean;
  onClose: () => void;
  rolePid: string;
  permissionPid: string;
  permissionLabel: string;
  schema: Record<string, PolicyFieldSchema>;
  initialValues?: Record<string, any>;
  decisionApi?: RuleBindingDecisionApi;
  onSuccess: () => void;
}

// ---------------------------------------------------------------------------
// Field renderer helpers
// ---------------------------------------------------------------------------

const inputClass =
  'block w-full rounded-md border border-gray-300 px-3 py-2 text-sm shadow-sm focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500 dark:border-gray-600 dark:bg-gray-800 dark:text-gray-100 disabled:cursor-not-allowed disabled:opacity-60';

interface FieldRendererProps {
  fieldKey: string;
  fieldSchema: PolicyFieldSchema;
  value: any;
  decisionApi?: RuleBindingDecisionApi;
  onChange: (key: string, value: any) => void;
}

function FieldRenderer({ fieldKey, fieldSchema, value, decisionApi, onChange }: FieldRendererProps) {
  const fieldId = `policy-field-${fieldKey}`;

  const labelEl = (
    <label
      htmlFor={fieldId}
      className="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1"
    >
      {fieldSchema.label}
      {fieldSchema.required && (
        <span className="ml-1 text-red-500" aria-hidden>
          *
        </span>
      )}
    </label>
  );

  switch (fieldSchema.type) {
    case 'number':
      return (
        <div>
          {labelEl}
          <input
            id={fieldId}
            type="number"
            className={inputClass}
            value={value ?? fieldSchema.default ?? ''}
            min={fieldSchema.min}
            max={fieldSchema.max}
            onChange={(e) =>
              onChange(fieldKey, e.target.value === '' ? null : Number(e.target.value))
            }
            data-testid={`policy-field-${fieldKey}`}
          />
          {fieldSchema.min !== undefined || fieldSchema.max !== undefined ? (
            <p className="mt-1 text-xs text-gray-400">
              {fieldSchema.min !== undefined && fieldSchema.max !== undefined
                ? `${fieldSchema.min} – ${fieldSchema.max}`
                : fieldSchema.min !== undefined
                  ? `Min: ${fieldSchema.min}`
                  : `Max: ${fieldSchema.max}`}
            </p>
          ) : null}
        </div>
      );

    case 'string':
      return (
        <div>
          {labelEl}
          <input
            id={fieldId}
            type="text"
            className={inputClass}
            value={value ?? fieldSchema.default ?? ''}
            onChange={(e) => onChange(fieldKey, e.target.value)}
            data-testid={`policy-field-${fieldKey}`}
          />
        </div>
      );

    case 'boolean': {
      const checked = value !== undefined ? Boolean(value) : Boolean(fieldSchema.default);
      return (
        <div className="flex items-center justify-between">
          <span className="text-sm font-medium text-gray-700 dark:text-gray-300">
            {fieldSchema.label}
          </span>
          <button
            id={fieldId}
            type="button"
            role="switch"
            aria-checked={checked}
            onClick={() => onChange(fieldKey, !checked)}
            className={`relative inline-flex h-5 w-9 flex-shrink-0 cursor-pointer rounded-full border-2 border-transparent transition-colors duration-200 ease-in-out focus:outline-none focus:ring-2 focus:ring-blue-500 focus:ring-offset-2 ${
              checked ? 'bg-blue-600' : 'bg-gray-200 dark:bg-gray-600'
            }`}
            data-testid={`policy-field-${fieldKey}`}
          >
            <span
              aria-hidden
              className={`pointer-events-none inline-block h-4 w-4 transform rounded-full bg-white shadow ring-0 transition duration-200 ease-in-out ${
                checked ? 'translate-x-4' : 'translate-x-0'
              }`}
            />
          </button>
        </div>
      );
    }

    case 'enum': {
      const options = fieldSchema.options ?? [];
      return (
        <div>
          {labelEl}
          <select
            id={fieldId}
            className={inputClass}
            value={value ?? fieldSchema.default ?? ''}
            onChange={(e) => onChange(fieldKey, e.target.value)}
            data-testid={`policy-field-${fieldKey}`}
          >
            {!fieldSchema.required && (
              <option value="">—</option>
            )}
            {options.map((opt) => (
              <option key={opt} value={opt}>
                {opt}
              </option>
            ))}
          </select>
        </div>
      );
    }

    case 'enum[]': {
      const options = fieldSchema.options ?? [];
      const selected: string[] = Array.isArray(value)
        ? value
        : Array.isArray(fieldSchema.default)
          ? fieldSchema.default
          : [];

      const toggleOption = (opt: string) => {
        const next = selected.includes(opt)
          ? selected.filter((v) => v !== opt)
          : [...selected, opt];
        onChange(fieldKey, next);
      };

      return (
        <div>
          {labelEl}
          <div
            className="flex flex-col gap-1.5 rounded-md border border-gray-200 p-2 dark:border-gray-700"
            data-testid={`policy-field-${fieldKey}`}
          >
            {options.length === 0 ? (
              <span className="text-xs text-gray-400 italic">No options defined</span>
            ) : (
              options.map((opt) => (
                <label key={opt} className="flex items-center gap-2 cursor-pointer">
                  <input
                    type="checkbox"
                    checked={selected.includes(opt)}
                    onChange={() => toggleOption(opt)}
                    className="h-4 w-4 rounded border-gray-300 text-blue-600 focus:ring-blue-500"
                  />
                  <span className="text-sm text-gray-700 dark:text-gray-300">{opt}</span>
                </label>
              ))
            )}
          </div>
        </div>
      );
    }

    case 'rule-center': {
      const current = value && typeof value === 'object' && !Array.isArray(value)
        ? (value as Record<string, any>)
        : {};
      const expectedMatched =
        current.expectedMatched !== undefined
          ? Boolean(current.expectedMatched)
          : fieldSchema.expectedMatched !== false;
      const timeoutMs = Number(current.timeoutMs ?? fieldSchema.timeoutMs ?? 50);
      const initialRuleBinding: RuleConsumerBindingDraft | undefined =
        current.ruleBinding ??
        (current.decisionBinding || current.conditionSpec
          ? {
              consumerType: 'PERMISSION',
              bindingKind: current.decisionBinding ? 'DECISION_REF' : 'CONDITION',
              decisionBinding: current.decisionBinding,
              conditionSpec: current.conditionSpec,
              enabled: current.enabled !== false,
            }
          : undefined);

      const emitRuleCenterValue = (
        patch: Partial<Record<string, any>>,
        nextRuleBinding: RuleConsumerBindingDraft | undefined = initialRuleBinding,
      ) => {
        const nextTimeout = Number(patch.timeoutMs ?? timeoutMs);
        const ruleBinding = nextRuleBinding
          ? {
              ...nextRuleBinding,
              consumerType: nextRuleBinding.consumerType ?? 'PERMISSION',
              decisionBinding: nextRuleBinding.decisionBinding
                ? {
                    ...nextRuleBinding.decisionBinding,
                    timeoutMs: nextTimeout,
                    fallbackPolicy:
                      nextRuleBinding.decisionBinding.fallbackPolicy ?? { mode: 'FAIL_CLOSED' },
                  }
                : nextRuleBinding.decisionBinding,
            }
          : undefined;
        onChange(fieldKey, {
          ...current,
          expectedMatched,
          timeoutMs: nextTimeout,
          enabled: current.enabled !== false,
          ...patch,
          ruleBinding,
        });
      };

      return (
        <div
          className="rounded-md border border-gray-200 bg-gray-50 p-3 dark:border-gray-700 dark:bg-gray-900/40"
          data-testid={`policy-field-${fieldKey}`}
        >
          <div className="mb-2 flex items-center justify-between gap-3">
            <div>
              {labelEl}
              <p className="text-xs text-gray-500 dark:text-gray-400">
                权限 ABAC 仅读取上下文字段，超时、异常或未命中兜底时默认阻断。
              </p>
            </div>
            <label className="flex items-center gap-2 text-xs text-gray-600 dark:text-gray-300">
              <input
                type="checkbox"
                checked={expectedMatched}
                onChange={(event) => emitRuleCenterValue({ expectedMatched: event.target.checked })}
                data-testid={`policy-rule-expected-${fieldKey}`}
              />
              要求命中
            </label>
          </div>

          <label className="mb-3 block text-xs font-medium text-gray-600 dark:text-gray-300">
            超时毫秒
            <input
              id={`${fieldId}-timeout`}
              type="number"
              min={1}
              max={1000}
              className={`${inputClass} mt-1`}
              value={Number.isFinite(timeoutMs) ? timeoutMs : 50}
              onChange={(event) =>
                emitRuleCenterValue({ timeoutMs: Number(event.target.value || 50) })
              }
              data-testid={`policy-rule-timeout-${fieldKey}`}
            />
          </label>

          <DecisionRuleBindingBlock
            value={initialRuleBinding}
            onChange={(next) => emitRuleCenterValue({}, next)}
            block={{
              props: {
                mode: fieldSchema.mode ?? 'combined',
                consumerType: 'PERMISSION',
                consumerNodeId: fieldKey,
                showImpactPreview: true,
                showTestRunner: true,
                fields: fieldSchema.fields,
                fieldCatalogMode: fieldSchema.fieldCatalogMode,
                fieldCatalogModelCode: fieldSchema.fieldCatalogModelCode,
                fieldCatalogModelCodeField: fieldSchema.fieldCatalogModelCodeField,
                decisions: fieldSchema.decisions,
                initialDecisionCode:
                  fieldSchema.initialDecisionCode ?? fieldSchema.decisions?.[0]?.code,
                initialContextJson: fieldSchema.initialContextJson,
                initialVersionPolicy: 'LATEST_PUBLISHED',
              },
            }}
            api={decisionApi}
          />
        </div>
      );
    }

    default:
      return null;
  }
}

// ---------------------------------------------------------------------------
// Main dialog component
// ---------------------------------------------------------------------------

export default function PolicyConfigDialog({
  open,
  onClose,
  rolePid,
  permissionPid,
  permissionLabel,
  schema,
  initialValues,
  decisionApi,
  onSuccess,
}: PolicyConfigDialogProps) {
  const { t } = useI18n();
  const { showSuccessToast, showErrorToast } = useToastContext();

  const [values, setValues] = useState<Record<string, any>>({});
  const [saving, setSaving] = useState(false);
  const text = useCallback(
    (key: string, fallback: string) => {
      const translated = t(key, undefined, fallback);
      return translated && translated !== key ? translated : fallback;
    },
    [t],
  );

  // Build initial values from schema defaults merged with passed-in initialValues
  useEffect(() => {
    if (!open) return;
    const defaults: Record<string, any> = {};
    for (const [key, field] of Object.entries(schema)) {
      defaults[key] = field.default ?? (field.type === 'enum[]' ? [] : undefined);
    }
    setValues({ ...defaults, ...(initialValues ?? {}) });
  }, [open, schema, initialValues]);

  const handleFieldChange = useCallback((key: string, value: any) => {
    setValues((prev) => ({ ...prev, [key]: value }));
  }, []);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();

    // Required field validation
    for (const [key, field] of Object.entries(schema)) {
      if (field.required) {
        const v = values[key];
        const isEmpty =
          v === null ||
          v === undefined ||
          v === '' ||
          (Array.isArray(v) && v.length === 0);
        if (isEmpty) {
          showErrorToast(
            text('admin.permission.policy.requiredError', '必填字段缺失') +
              ': ' +
              field.label,
          );
          return;
        }
      }
    }

    setSaving(true);
    try {
      await permissionService.setPolicy(rolePid, permissionPid, values);
      showSuccessToast(
        text('admin.permission.policy.saveSuccess', '策略配置已保存'),
      );
      onSuccess();
      onClose();
    } catch (err) {
      showErrorToast(
        text('admin.permission.policy.saveError', '策略配置保存失败'),
      );
    } finally {
      setSaving(false);
    }
  };

  const fieldEntries = Object.entries(schema);

  return (
    <Dialog open={open} onOpenChange={(isOpen) => !isOpen && onClose()}>
      <DialogContent className="h-[calc(100vh-2rem)] max-h-[calc(100vh-2rem)] max-w-3xl overflow-hidden p-0">
        <DialogHeader className="px-6 pt-6 pr-12">
          <DialogTitle className="flex items-center gap-2">
            <span>
              {text('admin.permission.policy.dialogTitle', '策略配置')}
              {' — '}
              <span className="font-normal text-gray-600 dark:text-gray-400">
                {permissionLabel}
              </span>
            </span>
          </DialogTitle>
        </DialogHeader>

        <form
          onSubmit={handleSubmit}
          className="min-h-0"
          data-testid="policy-config-form"
        >
          <div className="max-h-[calc(100vh-13rem)] space-y-4 overflow-y-auto px-6 py-4 pb-24">
            {fieldEntries.length === 0 ? (
              <p className="text-sm text-gray-400 italic">
                {text('admin.permission.policy.noFields', '此策略没有可配置字段。')}
              </p>
            ) : (
              fieldEntries.map(([key, fieldSchema]) => (
                <FieldRenderer
                  key={key}
                  fieldKey={key}
                  fieldSchema={fieldSchema}
                  value={values[key]}
                  decisionApi={decisionApi}
                  onChange={handleFieldChange}
                />
              ))
            )}
          </div>

          {/* Footer buttons */}
          <div className="absolute right-0 bottom-0 left-0 z-10 flex justify-end gap-3 border-t border-gray-100 bg-white px-6 py-4 dark:border-gray-700 dark:bg-gray-900">
            <button
              type="button"
              onClick={onClose}
              disabled={saving}
              className="rounded-md border border-gray-300 bg-white px-4 py-2 text-sm font-medium text-gray-700 hover:bg-gray-50 focus:outline-none focus:ring-2 focus:ring-blue-500 focus:ring-offset-2 disabled:opacity-60 dark:border-gray-600 dark:bg-gray-800 dark:text-gray-200 dark:hover:bg-gray-700"
            >
              {text('common.cancel', '取消')}
            </button>
            <button
              type="submit"
              disabled={saving}
              className="inline-flex items-center gap-2 rounded-md bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700 focus:outline-none focus:ring-2 focus:ring-blue-500 focus:ring-offset-2 disabled:opacity-60"
              data-testid="policy-save-button"
            >
              {saving ? (
                <>
                  <svg
                    className="h-4 w-4 animate-spin"
                    xmlns="http://www.w3.org/2000/svg"
                    fill="none"
                    viewBox="0 0 24 24"
                  >
                    <circle
                      className="opacity-25"
                      cx="12"
                      cy="12"
                      r="10"
                      stroke="currentColor"
                      strokeWidth="4"
                    />
                    <path
                      className="opacity-75"
                      fill="currentColor"
                      d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z"
                    />
                  </svg>
                  {text('common.saving', '保存中...')}
                </>
              ) : (
                text('common.save', '保存')
              )}
            </button>
          </div>
        </form>
      </DialogContent>
    </Dialog>
  );
}
