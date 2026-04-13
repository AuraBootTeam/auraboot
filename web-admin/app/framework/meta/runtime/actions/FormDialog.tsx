/**
 * FormDialog - Dynamic form dialog for dialog.form action
 *
 * Listens for 'dialog:form' CustomEvent on window and renders a modal
 * with configurable fields (Input, Select, InputNumber).
 *
 * Usage:
 * - Mount <FormDialog /> in the app (e.g. in dynamic.$tableName.tsx)
 * - The dialog.form action in ActionRegistry dispatches 'dialog:form' events
 * - On submit, calls onSubmit(formData) which stores values in stateManager
 * - On cancel, calls onCancel() which rejects the action promise
 */

import { useState, useEffect, useRef, useCallback } from 'react';
import { useI18n } from '~/contexts/I18nContext';
import { getLocalizedText } from '~/framework/meta/runtime/expression/i18n-renderer';

interface FormFieldConfig {
  field: string;
  label?: string;
  type?: 'text' | 'select' | 'number' | 'textarea';
  required?: boolean;
  placeholder?: string;
  defaultValue?: any;
  dataSource?: {
    type: 'api' | 'static';
    endpoint?: string;
    data?: Array<{ label: string; value: string }>;
  };
}

interface FormDialogState {
  open: boolean;
  title?: string;
  fields: FormFieldConfig[];
  fieldOptions: Record<string, Array<{ label: string; value: string }>>;
  defaults: Record<string, any>;
  onSubmit?: (formData: Record<string, any>) => void;
  onCancel?: () => void;
}

export default function FormDialog() {
  const { t, locale } = useI18n();
  const [state, setState] = useState<FormDialogState>({
    open: false,
    fields: [],
    fieldOptions: {},
    defaults: {},
  });
  const [formData, setFormData] = useState<Record<string, any>>({});
  const [errors, setErrors] = useState<Record<string, string>>({});
  const firstInputRef = useRef<HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement>(null);

  // Listen for dialog:form events
  useEffect(() => {
    const handler = (e: Event) => {
      const detail = (e as CustomEvent).detail;
      if (!detail) return;

      const defaults = detail.defaults || {};
      setState({
        open: true,
        title: detail.title,
        fields: detail.fields || [],
        fieldOptions: detail.fieldOptions || {},
        defaults,
        onSubmit: detail.onSubmit,
        onCancel: detail.onCancel,
      });
      setFormData({ ...defaults });
      setErrors({});
    };

    window.addEventListener('dialog:form', handler);
    return () => window.removeEventListener('dialog:form', handler);
  }, []);

  // Focus first input when dialog opens
  useEffect(() => {
    if (state.open) {
      // Delay to allow DOM to render
      const timer = setTimeout(() => {
        firstInputRef.current?.focus();
      }, 50);
      return () => clearTimeout(timer);
    }
  }, [state.open]);

  // Handle Escape key
  useEffect(() => {
    if (!state.open) return;

    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        handleCancel();
      }
    };
    document.addEventListener('keydown', handleKeyDown);
    return () => document.removeEventListener('keydown', handleKeyDown);
  }, [state.open]);

  const handleCancel = useCallback(() => {
    state.onCancel?.();
    setState((prev) => ({ ...prev, open: false }));
  }, [state.onCancel]);

  const handleSubmit = useCallback(() => {
    // Validate required fields
    const newErrors: Record<string, string> = {};
    for (const field of state.fields) {
      if (field.required) {
        const value = formData[field.field];
        if (value === undefined || value === null || value === '') {
          const label = field.label ? getLocalizedText(field.label, locale, t) : field.field;
          newErrors[field.field] = `${label} ${t('common.validation.required') || 'is required'}`;
        }
      }
    }

    if (Object.keys(newErrors).length > 0) {
      setErrors(newErrors);
      return;
    }

    state.onSubmit?.(formData);
    setState((prev) => ({ ...prev, open: false }));
  }, [state.fields, state.onSubmit, formData, locale, t]);

  const updateField = useCallback((fieldName: string, value: any) => {
    setFormData((prev) => ({ ...prev, [fieldName]: value }));
    // Clear error when user starts typing
    setErrors((prev) => {
      if (prev[fieldName]) {
        const next = { ...prev };
        delete next[fieldName];
        return next;
      }
      return prev;
    });
  }, []);

  if (!state.open) return null;

  const dialogTitle = state.title
    ? getLocalizedText(state.title, locale, t)
    : t('common.form') || 'Form';

  return (
    <div
      className="fixed inset-0 z-[60] flex items-center justify-center"
      data-testid="form-dialog"
    >
      {/* Backdrop */}
      <div
        className="absolute inset-0 bg-black/50 transition-opacity duration-200"
        onClick={handleCancel}
      />

      {/* Dialog */}
      <div className="relative mx-4 w-full max-w-lg scale-100 transform rounded-lg bg-white opacity-100 shadow-xl transition-all duration-200 dark:bg-gray-800">
        {/* Header */}
        <div className="border-b border-gray-200 px-6 pt-6 pb-4 dark:border-gray-700">
          <h3 className="text-lg font-semibold text-gray-900 dark:text-white">{dialogTitle}</h3>
        </div>

        {/* Body - Form Fields */}
        <div className="max-h-[60vh] space-y-4 overflow-y-auto px-6 py-4">
          {state.fields.map((field, index) => {
            const label = field.label ? getLocalizedText(field.label, locale, t) : field.field;
            const placeholder = field.placeholder
              ? getLocalizedText(field.placeholder, locale, t)
              : '';
            const fieldType = field.type || 'text';
            const options = state.fieldOptions[field.field] || [];
            const error = errors[field.field];
            const isFirst = index === 0;

            return (
              <div key={field.field}>
                <label className="mb-1 block text-sm font-medium text-gray-700 dark:text-gray-300">
                  {label}
                  {field.required && <span className="ml-1 text-red-500">*</span>}
                </label>

                {fieldType === 'select' ? (
                  <select
                    ref={
                      isFirst ? (firstInputRef as React.RefObject<HTMLSelectElement>) : undefined
                    }
                    data-testid={`form-dialog-field-${field.field}`}
                    value={formData[field.field] ?? ''}
                    onChange={(e) => updateField(field.field, e.target.value)}
                    className={`w-full rounded-lg border bg-white px-3 py-2 text-sm text-gray-900 transition-colors dark:bg-gray-700 dark:text-white ${
                      error
                        ? 'border-red-500 focus:border-red-500 focus:ring-red-500'
                        : 'border-gray-300 focus:border-blue-500 focus:ring-blue-500 dark:border-gray-600'
                    } focus:ring-2 focus:outline-none`}
                  >
                    <option value="">
                      {placeholder || `${t('common.select') || 'Select'}...`}
                    </option>
                    {options.map((opt) => (
                      <option key={opt.value} value={opt.value}>
                        {getLocalizedText(opt.label, locale, t)}
                      </option>
                    ))}
                  </select>
                ) : fieldType === 'number' ? (
                  <input
                    ref={isFirst ? (firstInputRef as React.RefObject<HTMLInputElement>) : undefined}
                    data-testid={`form-dialog-field-${field.field}`}
                    type="number"
                    value={formData[field.field] ?? ''}
                    onChange={(e) =>
                      updateField(field.field, e.target.value === '' ? '' : Number(e.target.value))
                    }
                    placeholder={placeholder}
                    className={`w-full rounded-lg border bg-white px-3 py-2 text-sm text-gray-900 placeholder-gray-400 transition-colors dark:bg-gray-700 dark:text-white dark:placeholder-gray-500 ${
                      error
                        ? 'border-red-500 focus:border-red-500 focus:ring-red-500'
                        : 'border-gray-300 focus:border-blue-500 focus:ring-blue-500 dark:border-gray-600'
                    } focus:ring-2 focus:outline-none`}
                  />
                ) : fieldType === 'textarea' ? (
                  <textarea
                    ref={
                      isFirst ? (firstInputRef as React.RefObject<HTMLTextAreaElement>) : undefined
                    }
                    data-testid={`form-dialog-field-${field.field}`}
                    value={formData[field.field] ?? ''}
                    onChange={(e) => updateField(field.field, e.target.value)}
                    placeholder={placeholder}
                    rows={3}
                    className={`w-full rounded-lg border bg-white px-3 py-2 text-sm text-gray-900 placeholder-gray-400 transition-colors dark:bg-gray-700 dark:text-white dark:placeholder-gray-500 ${
                      error
                        ? 'border-red-500 focus:border-red-500 focus:ring-red-500'
                        : 'border-gray-300 focus:border-blue-500 focus:ring-blue-500 dark:border-gray-600'
                    } resize-none focus:ring-2 focus:outline-none`}
                  />
                ) : (
                  <input
                    ref={isFirst ? (firstInputRef as React.RefObject<HTMLInputElement>) : undefined}
                    data-testid={`form-dialog-field-${field.field}`}
                    type="text"
                    value={formData[field.field] ?? ''}
                    onChange={(e) => updateField(field.field, e.target.value)}
                    placeholder={placeholder}
                    className={`w-full rounded-lg border bg-white px-3 py-2 text-sm text-gray-900 placeholder-gray-400 transition-colors dark:bg-gray-700 dark:text-white dark:placeholder-gray-500 ${
                      error
                        ? 'border-red-500 focus:border-red-500 focus:ring-red-500'
                        : 'border-gray-300 focus:border-blue-500 focus:ring-blue-500 dark:border-gray-600'
                    } focus:ring-2 focus:outline-none`}
                  />
                )}

                {error && <p className="mt-1 text-xs text-red-500">{error}</p>}
              </div>
            );
          })}
        </div>

        {/* Footer */}
        <div className="flex justify-end gap-3 border-t border-gray-200 px-6 py-4 dark:border-gray-700">
          <button
            data-testid="form-dialog-cancel"
            onClick={handleCancel}
            className="rounded-lg border border-gray-300 bg-white px-4 py-2 text-sm font-medium text-gray-700 transition-colors hover:bg-gray-50 focus:ring-2 focus:ring-gray-400 focus:ring-offset-2 focus:outline-none dark:border-gray-600 dark:bg-gray-700 dark:text-gray-300 dark:hover:bg-gray-600"
          >
            {t('common.cancel') || 'Cancel'}
          </button>
          <button
            data-testid="form-dialog-submit"
            onClick={handleSubmit}
            className="rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white transition-colors hover:bg-blue-700 focus:ring-2 focus:ring-blue-500 focus:ring-offset-2 focus:outline-none"
          >
            {t('common.confirm') || 'Confirm'}
          </button>
        </div>
      </div>
    </div>
  );
}
