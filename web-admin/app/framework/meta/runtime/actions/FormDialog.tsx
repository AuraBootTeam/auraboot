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
import { buildRequiredFieldMessage } from '~/framework/meta/utils/validationMessages';

interface VisibilityRule {
  field: string;
  operator?: 'equals' | 'notEquals' | 'in' | 'notIn' | 'empty' | 'notEmpty';
  value?: any;
  values?: any[];
}

interface FormOptionConfig {
  label: string | Record<string, string>;
  value: string;
  description?: string | Record<string, string>;
  disabled?: boolean;
  visibleWhen?: VisibilityRule;
}

interface FormFieldConfig {
  field: string;
  label?: string | Record<string, string>;
  type?: 'text' | 'select' | 'number' | 'textarea' | 'multiselect' | 'segmented' | 'checkbox';
  required?: boolean;
  mustBeTrue?: boolean;
  placeholder?: string | Record<string, string>;
  defaultValue?: any;
  searchable?: boolean;
  visibleWhen?: VisibilityRule;
  dataSource?: {
    type: 'api' | 'static';
    endpoint?: string;
    data?: FormOptionConfig[];
  };
}

interface FormDialogState {
  open: boolean;
  title?: string | Record<string, string>;
  fields: FormFieldConfig[];
  fieldOptions: Record<string, FormOptionConfig[]>;
  defaults: Record<string, any>;
  submitLabel?: string | Record<string, string>;
  onSubmit?: (formData: Record<string, any>) => void;
  onCancel?: () => void;
}

function matchesVisibility(rule: VisibilityRule | undefined, formData: Record<string, any>) {
  if (!rule) return true;
  const actual = formData[rule.field];
  const values = rule.values ?? (Array.isArray(rule.value) ? rule.value : []);
  switch (rule.operator ?? 'equals') {
    case 'notEquals':
      return actual !== rule.value;
    case 'in':
      return values.includes(actual);
    case 'notIn':
      return !values.includes(actual);
    case 'empty':
      return actual === undefined || actual === null || actual === ''
        || (Array.isArray(actual) && actual.length === 0);
    case 'notEmpty':
      return actual !== undefined && actual !== null && actual !== ''
        && (!Array.isArray(actual) || actual.length > 0);
    case 'equals':
    default:
      return actual === rule.value;
  }
}

function visibleOptions(
  field: FormFieldConfig,
  fieldOptions: Record<string, FormOptionConfig[]>,
  formData: Record<string, any>,
) {
  return (fieldOptions[field.field] || []).filter((option) =>
    matchesVisibility(option.visibleWhen, formData),
  );
}

function translatedOrFallback(
  t: (key: string) => string,
  key: string,
  fallback: string,
): string {
  const translated = t(key);
  return translated && translated !== key ? translated : fallback;
}

function isMissing(value: any) {
  return value === undefined || value === null || value === ''
    || (Array.isArray(value) && value.length === 0)
    || value === false;
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
  const [searchTerms, setSearchTerms] = useState<Record<string, string>>({});
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
        submitLabel: detail.submitLabel,
        onSubmit: detail.onSubmit,
        onCancel: detail.onCancel,
      });
      setFormData({ ...defaults });
      setErrors({});
      setSearchTerms({});
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
    const visibleFields = state.fields.filter((field) =>
      matchesVisibility(field.visibleWhen, formData),
    );
    const newErrors: Record<string, string> = {};
    for (const field of visibleFields) {
      const value = formData[field.field];
      if (field.required) {
        if (isMissing(value) || (field.mustBeTrue && value !== true)) {
          const label = field.label ? getLocalizedText(field.label, locale, t) : field.field;
          newErrors[field.field] = buildRequiredFieldMessage(label, {
            dataType: field.type,
            component: field.type,
            locale,
            t,
          });
        }
      }
      if (['select', 'segmented'].includes(field.type || '') && value !== undefined && value !== '') {
        const options = visibleOptions(field, state.fieldOptions, formData);
        if (!options.some((option) => option.value === value && !option.disabled)) {
          const label = field.label ? getLocalizedText(field.label, locale, t) : field.field;
          newErrors[field.field] = buildRequiredFieldMessage(label, {
            dataType: field.type,
            component: field.type,
            locale,
            t,
          });
        }
      }
    }

    if (Object.keys(newErrors).length > 0) {
      setErrors(newErrors);
      return;
    }

    const submitted = Object.fromEntries(
      visibleFields.map((field) => [field.field, formData[field.field]]),
    );
    state.onSubmit?.(submitted);
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
      <div className="relative mx-4 flex max-h-[calc(100vh-2rem)] w-full max-w-lg scale-100 transform flex-col rounded-lg bg-white opacity-100 shadow-xl transition-all duration-200 dark:bg-gray-800">
        {/* Header */}
        <div className="shrink-0 border-b border-gray-200 px-6 pt-6 pb-4 dark:border-gray-700">
          <h3 className="text-lg font-semibold text-gray-900 dark:text-white">{dialogTitle}</h3>
        </div>

        {/* Body - Form Fields */}
        <div
          className="min-h-0 flex-1 space-y-4 overflow-y-auto px-6 py-4 [scrollbar-gutter:stable]"
          data-testid="form-dialog-body"
        >
          {state.fields.map((field, index) => {
            if (!matchesVisibility(field.visibleWhen, formData)) return null;
            const label = field.label ? getLocalizedText(field.label, locale, t) : field.field;
            const placeholder = field.placeholder
              ? getLocalizedText(field.placeholder, locale, t)
              : '';
            const fieldType = field.type || 'text';
            const options = visibleOptions(field, state.fieldOptions, formData);
            const error = errors[field.field];
            const isFirst = index === 0;
            const searchTerm = searchTerms[field.field] || '';
            const filteredOptions = field.searchable && searchTerm
              ? options.filter((option) => {
                  const optionLabel = getLocalizedText(option.label, locale, t);
                  const description = option.description
                    ? getLocalizedText(option.description, locale, t)
                    : '';
                  const keyword = searchTerm.toLocaleLowerCase();
                  return `${optionLabel} ${description}`.toLocaleLowerCase().includes(keyword);
                })
              : options;

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
                      {placeholder || `${translatedOrFallback(
                        t,
                        'common.select',
                        locale.startsWith('zh') ? '请选择' : 'Select',
                      )}...`}
                    </option>
                    {options.map((opt) => (
                      <option key={opt.value} value={opt.value}>
                        {getLocalizedText(opt.label, locale, t)}
                      </option>
                    ))}
                  </select>
                ) : fieldType === 'segmented' ? (
                  <div
                    role="radiogroup"
                    data-testid={`form-dialog-field-${field.field}`}
                    className="grid grid-cols-2 gap-2 sm:grid-cols-3"
                  >
                    {options.map((option) => {
                      const selected = formData[field.field] === option.value;
                      return (
                        <button
                          key={option.value}
                          type="button"
                          role="radio"
                          aria-checked={selected}
                          disabled={option.disabled}
                          onClick={() => updateField(field.field, option.value)}
                          className={`min-h-10 rounded-md border px-3 py-2 text-sm font-medium transition-colors focus:ring-2 focus:ring-blue-500 focus:outline-none disabled:cursor-not-allowed disabled:opacity-50 ${
                            selected
                              ? 'border-blue-600 bg-blue-50 text-blue-700 dark:bg-blue-950 dark:text-blue-200'
                              : 'border-gray-300 bg-white text-gray-700 hover:bg-gray-50 dark:border-gray-600 dark:bg-gray-700 dark:text-gray-200 dark:hover:bg-gray-600'
                          }`}
                        >
                          {getLocalizedText(option.label, locale, t)}
                        </button>
                      );
                    })}
                  </div>
                ) : fieldType === 'multiselect' ? (
                  <div
                    data-testid={`form-dialog-field-${field.field}`}
                    className={`overflow-hidden rounded-lg border ${
                      error ? 'border-red-500' : 'border-gray-300 dark:border-gray-600'
                    }`}
                  >
                    {field.searchable && (
                      <input
                        ref={isFirst ? (firstInputRef as React.RefObject<HTMLInputElement>) : undefined}
                        type="search"
                        value={searchTerm}
                        onChange={(event) => setSearchTerms((prev) => ({
                          ...prev,
                          [field.field]: event.target.value,
                        }))}
                        placeholder={placeholder}
                        className="w-full border-0 border-b border-gray-200 bg-white px-3 py-2 text-sm text-gray-900 placeholder-gray-400 focus:ring-2 focus:ring-inset focus:ring-blue-500 focus:outline-none dark:border-gray-600 dark:bg-gray-700 dark:text-white"
                      />
                    )}
                    <div className="max-h-48 overflow-y-auto bg-white dark:bg-gray-700">
                      {filteredOptions.map((option) => {
                        const selected = Array.isArray(formData[field.field])
                          && formData[field.field].includes(option.value);
                        return (
                          <label
                            key={option.value}
                            className={`flex min-h-11 cursor-pointer items-start gap-3 border-b border-gray-100 px-3 py-2 last:border-b-0 hover:bg-gray-50 dark:border-gray-600 dark:hover:bg-gray-600 ${
                              option.disabled ? 'cursor-not-allowed opacity-50' : ''
                            }`}
                          >
                            <input
                              type="checkbox"
                              checked={selected}
                              disabled={option.disabled}
                              onChange={() => {
                                const current = Array.isArray(formData[field.field])
                                  ? formData[field.field]
                                  : [];
                                updateField(
                                  field.field,
                                  selected
                                    ? current.filter((value: string) => value !== option.value)
                                    : [...current, option.value],
                                );
                              }}
                              className="mt-0.5 h-4 w-4 rounded border-gray-300 text-blue-600 focus:ring-blue-500"
                            />
                            <span className="min-w-0">
                              <span className="block text-sm font-medium text-gray-900 dark:text-white">
                                {getLocalizedText(option.label, locale, t)}
                              </span>
                              {option.description && (
                                <span className="mt-0.5 block text-xs text-gray-500 dark:text-gray-300">
                                  {getLocalizedText(option.description, locale, t)}
                                </span>
                              )}
                            </span>
                          </label>
                        );
                      })}
                      {filteredOptions.length === 0 && (
                        <div className="px-3 py-6 text-center text-sm text-gray-500 dark:text-gray-300">
                          {t('common.noData') || 'No data'}
                        </div>
                      )}
                    </div>
                  </div>
                ) : fieldType === 'checkbox' ? (
                  <label
                    data-testid={`form-dialog-field-${field.field}`}
                    className={`flex min-h-11 cursor-pointer items-center gap-3 rounded-lg border px-3 py-2 ${
                      error
                        ? 'border-red-500'
                        : 'border-gray-300 bg-white dark:border-gray-600 dark:bg-gray-700'
                    }`}
                  >
                    <input
                      ref={isFirst ? (firstInputRef as React.RefObject<HTMLInputElement>) : undefined}
                      type="checkbox"
                      checked={formData[field.field] === true}
                      onChange={(event) => updateField(field.field, event.target.checked)}
                      className="h-4 w-4 rounded border-gray-300 text-blue-600 focus:ring-blue-500"
                    />
                    <span className="text-sm text-gray-700 dark:text-gray-200">{placeholder || label}</span>
                  </label>
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
        <div className="flex shrink-0 justify-end gap-3 border-t border-gray-200 px-6 py-4 dark:border-gray-700">
          <button
            data-testid="form-dialog-cancel"
            onClick={handleCancel}
            className="rounded-lg border border-gray-300 bg-white px-4 py-2 text-sm font-medium text-gray-700 transition-colors hover:bg-gray-50 focus:ring-2 focus:ring-gray-400 focus:ring-offset-2 focus:outline-none dark:border-gray-600 dark:bg-gray-700 dark:text-gray-300 dark:hover:bg-gray-600"
          >
            {translatedOrFallback(t, 'common.cancel', locale.startsWith('zh') ? '取消' : 'Cancel')}
          </button>
          <button
            data-testid="form-dialog-submit"
            onClick={handleSubmit}
            className="rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white transition-colors hover:bg-blue-700 focus:ring-2 focus:ring-blue-500 focus:ring-offset-2 focus:outline-none"
          >
            {state.submitLabel
              ? getLocalizedText(state.submitLabel, locale, t)
              : translatedOrFallback(
                  t,
                  'common.confirm',
                  locale.startsWith('zh') ? '确认' : 'Confirm',
                )}
          </button>
        </div>
      </div>
    </div>
  );
}
