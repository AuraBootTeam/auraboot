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

// ---------------------------------------------------------------------------
// Policy schema field types
// ---------------------------------------------------------------------------

interface PolicyFieldSchema {
  type: 'number' | 'string' | 'boolean' | 'enum' | 'enum[]';
  label: string;
  default?: any;
  required?: boolean;
  min?: number;
  max?: number;
  options?: string[];
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
  onChange: (key: string, value: any) => void;
}

function FieldRenderer({ fieldKey, fieldSchema, value, onChange }: FieldRendererProps) {
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
  onSuccess,
}: PolicyConfigDialogProps) {
  const { t } = useI18n();
  const { showSuccessToast, showErrorToast } = useToastContext();

  const [values, setValues] = useState<Record<string, any>>({});
  const [saving, setSaving] = useState(false);

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
            (t('admin.permission.policy.requiredError') || 'Required field missing') +
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
        t('admin.permission.policy.saveSuccess') || 'Policy configuration saved',
      );
      onSuccess();
      onClose();
    } catch (err) {
      showErrorToast(
        t('admin.permission.policy.saveError') || 'Failed to save policy configuration',
      );
    } finally {
      setSaving(false);
    }
  };

  const fieldEntries = Object.entries(schema);

  return (
    <Dialog open={open} onOpenChange={(isOpen) => !isOpen && onClose()}>
      <DialogContent className="max-w-lg">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2">
            <span>⚙️</span>
            <span>
              {t('admin.permission.policy.dialogTitle') || 'Policy Configuration'}
              {' — '}
              <span className="font-normal text-gray-600 dark:text-gray-400">
                {permissionLabel}
              </span>
            </span>
          </DialogTitle>
        </DialogHeader>

        <form onSubmit={handleSubmit} className="mt-4 space-y-4" data-testid="policy-config-form">
          {fieldEntries.length === 0 ? (
            <p className="text-sm text-gray-400 italic">
              {t('admin.permission.policy.noFields') || 'No configurable fields in this policy.'}
            </p>
          ) : (
            fieldEntries.map(([key, fieldSchema]) => (
              <FieldRenderer
                key={key}
                fieldKey={key}
                fieldSchema={fieldSchema}
                value={values[key]}
                onChange={handleFieldChange}
              />
            ))
          )}

          {/* Footer buttons */}
          <div className="flex justify-end gap-3 pt-2 border-t border-gray-100 dark:border-gray-700">
            <button
              type="button"
              onClick={onClose}
              disabled={saving}
              className="rounded-md border border-gray-300 bg-white px-4 py-2 text-sm font-medium text-gray-700 hover:bg-gray-50 focus:outline-none focus:ring-2 focus:ring-blue-500 focus:ring-offset-2 disabled:opacity-60 dark:border-gray-600 dark:bg-gray-800 dark:text-gray-200 dark:hover:bg-gray-700"
            >
              {t('common.cancel') || 'Cancel'}
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
                  {t('common.saving') || 'Saving...'}
                </>
              ) : (
                t('common.save') || 'Save'
              )}
            </button>
          </div>
        </form>
      </DialogContent>
    </Dialog>
  );
}
